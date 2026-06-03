//! 原生音频分析 —— Symphonia 解码 + 自己写的 BPM/能量/动态范围。
//!
//! 设计：
//!   - **不取代** 现有的 JS `audio-analysis.ts`（vocal entry / drum entry / outro
//!     这种"接歌用"的细节信号留在 JS 里，跟 mix-planner 紧耦合）。
//!   - 这一层只产 **AI 选曲提示用** 的声学特征：BPM、整曲响度（dBFS）、动态范围、
//!     谱重心（音色亮度）、前奏/尾奏能量。够 LLM prompt 用。
//!   - 跑 Rust 比 JS 快 5-10×，整库 200 首扫描从 30s+ 降到 5-8s。
//!
//! 算法：
//!   - 解码：Symphonia → 全部 sample 拼成单声道 Float32 (mono mix down)，按需重采样到 22050Hz
//!   - BPM：低通 200Hz → onset envelope（spectral flux 简化版：能量差正部分）→
//!          自相关在 60-200 BPM 区间找最大峰
//!   - 响度：整曲 RMS（dBFS），头 8s / 尾 8s 单独算，给 mix-planner 用
//!   - 结构：低频/人声频段的秒级能量，用来估计鼓点入口、人声入口、尾奏起点
//!   - 动态范围：peak dBFS - mean dBFS（粗略 DR，不是 EBU DR）
//!   - 谱重心：FFT 一帧的频率加权均值，反映"亮 / 暗"
//!
//! 失败回落：解码失败、太短（<8s）、采样数据全 0 → None，调用方决定怎么办。

use std::fs::File;
use std::path::Path;

use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use symphonia::core::audio::{AudioBufferRef, Signal};
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_NULL};
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

const TARGET_SR: u32 = 22050;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Acoustics {
    pub track_id: i64,
    pub duration_s: f32,
    /// 60-200 BPM；解不出来 → None
    pub bpm: Option<f32>,
    /// 0..1 自相关主峰强度
    pub bpm_confidence: f32,
    /// 前 6 秒内最明显的低频 onset，给单播放器 AutoMix 找 phrase 边界用。
    pub first_beat_s: Option<f32>,
    /// 整曲 RMS dBFS（负数）
    pub rms_db: f32,
    /// 整曲峰值 dBFS（≤0）
    pub peak_db: f32,
    /// peak_db - rms_db，越大动态范围越大
    pub dynamic_range_db: f32,
    /// 头 8 秒 RMS（线性能量 0..1，非 dB）
    pub intro_energy: f32,
    /// 尾 8 秒 RMS
    pub outro_energy: f32,
    /// 头 8 秒低频 RMS，判断下一首是否一进来就很重。
    #[serde(default)]
    pub intro_low_energy: f32,
    /// 尾 8 秒低频 RMS，判断当前歌尾部是否还很满。
    #[serde(default)]
    pub outro_low_energy: f32,
    /// 头 8 秒人声密度代理，0..1。
    #[serde(default)]
    pub intro_vocal_density: f32,
    /// 尾 8 秒人声密度代理，0..1。
    #[serde(default)]
    pub outro_vocal_density: f32,
    /// 第一次明显低频鼓/节拍入口（秒）。
    pub drum_entry_s: Option<f32>,
    /// 第一段人声入口（秒）。
    pub vocal_entry_s: Option<f32>,
    /// 最后一段人声结束后的尾奏起点（秒）；如果人声唱到文件尾则为 None。
    pub outro_start_s: Option<f32>,
    /// 谱重心（Hz）—— 音色亮度的代理。男声唱大约 1500，女声 2500，金属 4000+
    pub spectral_centroid_hz: f32,
    /// 粗略主 pitch class，0=C, 1=C#, ... 11=B。不是 mastering 级调性，只做兼容性弱信号。
    pub tonal_key: Option<i32>,
    /// tonal_key 的置信度，0..1。
    #[serde(default)]
    pub tonal_confidence: f32,
    /// 头部连续静音长度（秒）—— 第一帧 RMS > -50 dBFS 之前的时长。
    /// "natural" silence（如 fade-in 前的静默），跟编码器 padding 不一样。
    pub head_silence_s: f32,
    /// 尾部连续静音长度（秒）—— 同上，反向扫
    pub tail_silence_s: f32,
}

/// 主入口：从 cache 中取得的本地文件路径出发，跑完整分析。
///
/// `path` 不需要符合特定扩展名；Symphonia 用 magic bytes + 扩展名混合 hint。
pub fn analyze_file(track_id: i64, path: &Path) -> Result<Acoustics> {
    let samples = decode_to_mono(path)?;
    if samples.len() < TARGET_SR as usize * 8 {
        return Err(anyhow!(
            "audio too short: {} samples ({}s)",
            samples.len(),
            samples.len() as f32 / TARGET_SR as f32
        ));
    }

    let duration_s = samples.len() as f32 / TARGET_SR as f32;
    let (rms_db, peak_db) = rms_and_peak_db(&samples);

    let intro_window = (TARGET_SR as usize * 8).min(samples.len());
    let intro_energy = rms_lin(&samples[..intro_window]);
    let outro_start = samples.len().saturating_sub(TARGET_SR as usize * 8);
    let outro_energy = rms_lin(&samples[outro_start..]);

    let lowband = lowpass(&samples, TARGET_SR as f32, 200.0);
    let vocal_band = biquad_filter(
        &biquad_filter(
            &samples,
            TARGET_SR as f32,
            BiquadKind::Highpass,
            250.0,
            0.707,
        ),
        TARGET_SR as f32,
        BiquadKind::Lowpass,
        3500.0,
        0.707,
    );
    let energy_per_sec = rms_per_second(&samples, TARGET_SR);
    let low_energy_per_sec = rms_per_second(&lowband, TARGET_SR);
    let vocal_energy_per_sec = rms_per_second(&vocal_band, TARGET_SR);
    let vocal_per_sec =
        compute_vocal_prob(&energy_per_sec, &vocal_energy_per_sec, &low_energy_per_sec);
    let total_sec = energy_per_sec.len();
    let intro_low_energy = mean_window(&low_energy_per_sec, 0, 8);
    let outro_low_energy = mean_window(&low_energy_per_sec, total_sec.saturating_sub(8), total_sec);
    let intro_vocal_density = mean_window(&vocal_per_sec, 0, 8);
    let outro_vocal_density = mean_window(&vocal_per_sec, total_sec.saturating_sub(8), total_sec);
    let drum_entry_s = detect_drum_entry(&low_energy_per_sec);
    let vocal_entry_s = detect_vocal_entry(&vocal_per_sec);
    let outro_start_s = detect_outro_start(&vocal_per_sec, total_sec);
    let (bpm, bpm_conf, first_beat_s) = detect_bpm(&lowband);

    let spectral_centroid_hz = spectral_centroid(&samples, TARGET_SR);
    let (tonal_key, tonal_confidence) = estimate_tonal_key(&samples, TARGET_SR);
    let (head_silence_s, tail_silence_s) = silence_boundaries(&samples, TARGET_SR);

    Ok(Acoustics {
        track_id,
        duration_s,
        bpm,
        bpm_confidence: bpm_conf,
        first_beat_s,
        rms_db,
        peak_db,
        dynamic_range_db: peak_db - rms_db,
        intro_energy,
        outro_energy,
        intro_low_energy,
        outro_low_energy,
        intro_vocal_density,
        outro_vocal_density,
        drum_entry_s,
        vocal_entry_s,
        outro_start_s,
        spectral_centroid_hz,
        tonal_key,
        tonal_confidence,
        head_silence_s,
        tail_silence_s,
    })
}

/// 找 head / tail 静音段。把样本分成 50ms 帧，逐帧算 RMS dBFS。
/// 从两端往内扫，遇到第一个 > -50 dBFS 的帧就停。
///
/// 50ms 是兼容大多数 fade-in / pre-roll 的精度；阈值 -50 dBFS 比纯 0 检测更宽松，
/// 能处理"几乎不可闻的电平噪声"那种"看起来是静音但有底噪"的情况。
fn silence_boundaries(samples: &[f32], sr: u32) -> (f32, f32) {
    let frame_size = (sr as f32 * 0.05) as usize;
    if frame_size == 0 || samples.len() < frame_size {
        return (0.0, 0.0);
    }
    let frame_count = samples.len() / frame_size;
    let frame_dur = frame_size as f32 / sr as f32;
    // 用 RMS dBFS。-50 dBFS ≈ 线性 0.00316，听感上完全静音
    let threshold_lin = 0.00316_f32;

    let frame_rms = |i: usize| -> f32 {
        let start = i * frame_size;
        let end = (start + frame_size).min(samples.len());
        let mut sum = 0.0_f32;
        for &s in &samples[start..end] {
            sum += s * s;
        }
        (sum / (end - start) as f32).sqrt()
    };

    let mut head_frames = 0usize;
    for i in 0..frame_count {
        if frame_rms(i) > threshold_lin {
            break;
        }
        head_frames += 1;
    }
    let mut tail_frames = 0usize;
    for i in (0..frame_count).rev() {
        if frame_rms(i) > threshold_lin {
            break;
        }
        tail_frames += 1;
        if tail_frames + head_frames >= frame_count {
            break; // 整曲静默 —— 不可能但防御
        }
    }
    (
        head_frames as f32 * frame_dur,
        tail_frames as f32 * frame_dur,
    )
}

// ---------- 解码 ----------

/// Symphonia 解码 → 单声道 Float32，重采样到 22050 Hz。
///
/// 简化版：用线性插值重采样（够分析用，不做 anti-aliasing 滤镜）。
fn decode_to_mono(path: &Path) -> Result<Vec<f32>> {
    let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|s| s.to_str()) {
        hint.with_extension(ext);
    }

    let probed = symphonia::default::get_probe()
        .format(
            &hint,
            mss,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )
        .map_err(|e| anyhow!("symphonia probe: {e}"))?;
    let mut format = probed.format;

    let track = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
        .ok_or_else(|| anyhow!("no audio track"))?;
    let track_id = track.id;
    let src_sr = track
        .codec_params
        .sample_rate
        .ok_or_else(|| anyhow!("missing sample rate"))?;
    let mut decoder = symphonia::default::get_codecs()
        .make(&track.codec_params, &DecoderOptions::default())
        .map_err(|e| anyhow!("symphonia decoder: {e}"))?;

    let mut mono: Vec<f32> = Vec::new();

    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
            // EOF / 末尾的 reset 算正常结束
            Err(symphonia::core::errors::Error::IoError(ref e))
                if e.kind() == std::io::ErrorKind::UnexpectedEof =>
            {
                break;
            }
            Err(symphonia::core::errors::Error::ResetRequired) => break,
            Err(e) => return Err(anyhow!("symphonia next_packet: {e}")),
        };
        if packet.track_id() != track_id {
            continue;
        }
        let decoded = match decoder.decode(&packet) {
            Ok(d) => d,
            // 单帧解码错通常是流头信息出错，能继续就继续
            Err(symphonia::core::errors::Error::DecodeError(_)) => continue,
            Err(e) => return Err(anyhow!("symphonia decode: {e}")),
        };
        append_mono(decoded, &mut mono);
    }

    if src_sr == TARGET_SR {
        Ok(mono)
    } else {
        Ok(resample_linear(&mono, src_sr, TARGET_SR))
    }
}

/// 任意 channel layout / 任意 sample type → 单声道 f32。
fn append_mono(buf: AudioBufferRef<'_>, out: &mut Vec<f32>) {
    match buf {
        AudioBufferRef::F32(b) => mix_with_conv(&b, out, |s| s),
        AudioBufferRef::F64(b) => mix_with_conv(&b, out, |s| s as f32),
        AudioBufferRef::S8(b) => mix_with_conv(&b, out, |s| s as f32 / i8::MAX as f32),
        AudioBufferRef::S16(b) => mix_with_conv(&b, out, |s| s as f32 / i16::MAX as f32),
        AudioBufferRef::S24(b) => mix_with_conv(&b, out, |s| s.0 as f32 / 8388607.0),
        AudioBufferRef::S32(b) => mix_with_conv(&b, out, |s| s as f32 / i32::MAX as f32),
        AudioBufferRef::U8(b) => mix_with_conv(&b, out, |s| (s as f32 - 128.0) / 128.0),
        AudioBufferRef::U16(b) => mix_with_conv(&b, out, |s| (s as f32 - 32768.0) / 32768.0),
        AudioBufferRef::U24(b) => mix_with_conv(&b, out, |s| (s.0 as f32 - 8388608.0) / 8388608.0),
        AudioBufferRef::U32(b) => {
            mix_with_conv(&b, out, |s| (s as f32 - 2147483648.0) / 2147483648.0)
        }
    }
}

fn mix_with_conv<S: symphonia::core::sample::Sample + Copy>(
    buf: &symphonia::core::audio::AudioBuffer<S>,
    out: &mut Vec<f32>,
    conv: impl Fn(S) -> f32,
) {
    let frames = buf.frames();
    let chans = buf.spec().channels.count().max(1);
    out.reserve(frames);
    for f in 0..frames {
        let mut sum = 0.0f32;
        for c in 0..chans {
            sum += conv(buf.chan(c)[f]);
        }
        out.push(sum / chans as f32);
    }
}

/// 线性插值重采样 —— 不做 anti-aliasing。仅用于分析，听感无所谓。
fn resample_linear(input: &[f32], src_sr: u32, dst_sr: u32) -> Vec<f32> {
    if input.is_empty() || src_sr == dst_sr {
        return input.to_vec();
    }
    let ratio = src_sr as f64 / dst_sr as f64;
    let dst_len = ((input.len() as f64) / ratio).floor() as usize;
    let mut out = Vec::with_capacity(dst_len);
    for i in 0..dst_len {
        let pos = i as f64 * ratio;
        let idx = pos.floor() as usize;
        let frac = (pos - idx as f64) as f32;
        let a = input[idx];
        let b = if idx + 1 < input.len() {
            input[idx + 1]
        } else {
            a
        };
        out.push(a + (b - a) * frac);
    }
    out
}

// ---------- 算法 ----------

fn rms_lin(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let mut sum = 0.0f64;
    for &s in samples {
        sum += (s as f64) * (s as f64);
    }
    (sum / samples.len() as f64).sqrt() as f32
}

fn rms_and_peak_db(samples: &[f32]) -> (f32, f32) {
    if samples.is_empty() {
        return (-100.0, -100.0);
    }
    let mut sum_sq = 0.0f64;
    let mut peak = 0.0f32;
    for &s in samples {
        sum_sq += (s as f64) * (s as f64);
        let a = s.abs();
        if a > peak {
            peak = a;
        }
    }
    let rms = (sum_sq / samples.len() as f64).sqrt() as f32;
    let rms_db = if rms > 1e-7 {
        20.0 * rms.log10()
    } else {
        -100.0
    };
    let peak_db = if peak > 1e-7 {
        20.0 * peak.log10()
    } else {
        -100.0
    };
    (rms_db, peak_db)
}

/// 一阶 RC 低通 —— 简单够用。fc 是截止频率（Hz）。
fn lowpass(input: &[f32], sr: f32, fc: f32) -> Vec<f32> {
    let dt = 1.0 / sr;
    let rc = 1.0 / (2.0 * std::f32::consts::PI * fc);
    let alpha = dt / (rc + dt);
    let mut out = Vec::with_capacity(input.len());
    let mut prev = 0.0;
    for &x in input {
        prev = prev + alpha * (x - prev);
        out.push(prev);
    }
    out
}

enum BiquadKind {
    Lowpass,
    Highpass,
}

fn biquad_filter(input: &[f32], sr: f32, kind: BiquadKind, fc: f32, q: f32) -> Vec<f32> {
    let w0 = std::f32::consts::TAU * fc / sr;
    let cosw = w0.cos();
    let sinw = w0.sin();
    let alpha = sinw / (2.0 * q);
    let a0 = 1.0 + alpha;
    let (b0, b1, b2) = match kind {
        BiquadKind::Lowpass => (
            ((1.0 - cosw) / 2.0) / a0,
            (1.0 - cosw) / a0,
            ((1.0 - cosw) / 2.0) / a0,
        ),
        BiquadKind::Highpass => (
            ((1.0 + cosw) / 2.0) / a0,
            (-(1.0 + cosw)) / a0,
            ((1.0 + cosw) / 2.0) / a0,
        ),
    };
    let a1 = (-2.0 * cosw) / a0;
    let a2 = (1.0 - alpha) / a0;
    let mut out = Vec::with_capacity(input.len());
    let (mut x1, mut x2, mut y1, mut y2) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
    for &x0 in input {
        let y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        out.push(y0);
        x2 = x1;
        x1 = x0;
        y2 = y1;
        y1 = y0;
    }
    out
}

fn rms_per_second(samples: &[f32], sr: u32) -> Vec<f32> {
    let sr = sr as usize;
    let total_sec = samples.len() / sr;
    let mut out = Vec::with_capacity(total_sec);
    for s in 0..total_sec {
        let start = s * sr;
        let end = start + sr;
        out.push(rms_lin(&samples[start..end]));
    }
    out
}

fn compute_vocal_prob(total: &[f32], vocal: &[f32], low: &[f32]) -> Vec<f32> {
    let n = total.len().min(vocal.len()).min(low.len());
    let mut raw = Vec::with_capacity(n);
    for i in 0..n {
        let t = total[i] + 1e-6;
        let v = vocal[i] / t;
        let l = low[i] / t;
        raw.push((v * 1.4 - l * 0.6 - 0.12).clamp(0.0, 1.0));
    }
    median_filter(&raw, 5)
}

fn median_filter(input: &[f32], window: usize) -> Vec<f32> {
    if input.is_empty() || window <= 1 {
        return input.to_vec();
    }
    let half = window / 2;
    let mut out = Vec::with_capacity(input.len());
    for i in 0..input.len() {
        let start = i.saturating_sub(half);
        let end = (i + half + 1).min(input.len());
        let mut slice = input[start..end].to_vec();
        slice.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        out.push(slice[slice.len() / 2]);
    }
    out
}

fn mean_window(arr: &[f32], start: usize, end: usize) -> f32 {
    let a = start.min(arr.len());
    let b = end.min(arr.len());
    if b <= a {
        return 0.0;
    }
    arr[a..b].iter().copied().sum::<f32>() / (b - a) as f32
}

fn median(arr: &[f32]) -> f32 {
    if arr.is_empty() {
        return 0.0;
    }
    let mut s = arr.to_vec();
    s.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    s[s.len() / 2]
}

fn detect_drum_entry(low_per_sec: &[f32]) -> Option<f32> {
    if low_per_sec.len() < 10 {
        return None;
    }
    let baseline = median(&low_per_sec[..low_per_sec.len().min(5)]);
    let threshold = (baseline * 1.4).max(0.02);
    let mut run = 0usize;
    for (i, &v) in low_per_sec.iter().enumerate() {
        if v >= threshold {
            run += 1;
            if run >= 2 {
                return Some((i + 1 - run) as f32);
            }
        } else {
            run = 0;
        }
    }
    None
}

fn detect_vocal_entry(vocal_per_sec: &[f32]) -> Option<f32> {
    let mut run = 0usize;
    for (i, &v) in vocal_per_sec.iter().enumerate() {
        if v >= 0.5 {
            run += 1;
            if run >= 2 {
                return Some((i + 1 - run) as f32);
            }
        } else {
            run = 0;
        }
    }
    None
}

fn detect_outro_start(vocal_per_sec: &[f32], total_sec: usize) -> Option<f32> {
    let mut last_vocal_end: Option<usize> = None;
    let mut in_vocal = false;
    for (i, &v) in vocal_per_sec.iter().enumerate() {
        if v >= 0.5 {
            in_vocal = true;
        } else if in_vocal {
            last_vocal_end = Some(i);
            in_vocal = false;
        }
    }
    if in_vocal {
        return None;
    }
    let last = last_vocal_end?;
    if total_sec.saturating_sub(last) < 3 {
        return None;
    }
    let start = (last + 1).min(total_sec.saturating_sub(2));
    (start > 0).then_some(start as f32)
}

/// BPM 检测：能量包络（hop=256, ~86 fps）→ 高通去 DC → 自相关在 60-200 BPM 找峰。
///
/// 返回 (bpm, confidence)。confidence 是主峰高度归一化到 0..1，0.3 以上算可信。
fn detect_bpm(lowband: &[f32]) -> (Option<f32>, f32, Option<f32>) {
    const HOP: usize = 256;
    let frame_count = lowband.len() / HOP;
    if frame_count < 64 {
        return (None, 0.0, None);
    }
    let frame_rate = TARGET_SR as f32 / HOP as f32; // ~86 Hz

    // 帧能量
    let mut env: Vec<f32> = Vec::with_capacity(frame_count);
    for i in 0..frame_count {
        let start = i * HOP;
        let end = start + HOP;
        let mut e = 0.0f32;
        for &s in &lowband[start..end] {
            e += s * s;
        }
        env.push(e);
    }

    // onset = 能量正向增量（spectral flux 简化版）
    let mut onset: Vec<f32> = Vec::with_capacity(env.len());
    onset.push(0.0);
    for i in 1..env.len() {
        let d = env[i] - env[i - 1];
        onset.push(d.max(0.0));
    }
    // 减均值（去 DC）
    let mean: f32 = onset.iter().copied().sum::<f32>() / onset.len() as f32;
    for v in onset.iter_mut() {
        *v -= mean;
    }

    // 自相关：lag 范围 = 60 BPM..200 BPM
    let lag_min = (frame_rate * 60.0 / 200.0).floor() as usize; // 200 BPM
    let lag_max = (frame_rate * 60.0 / 60.0).ceil() as usize; // 60 BPM
    let lag_max = lag_max.min(env.len() / 2);
    if lag_min >= lag_max {
        return (None, 0.0, None);
    }

    // 自相关系数 r(lag)
    let mut best_lag = 0usize;
    let mut best_val = f32::NEG_INFINITY;
    let mut energy0 = 0.0f32;
    for &v in &onset {
        energy0 += v * v;
    }
    for lag in lag_min..=lag_max {
        let mut sum = 0.0f32;
        for i in 0..(onset.len() - lag) {
            sum += onset[i] * onset[i + lag];
        }
        if sum > best_val {
            best_val = sum;
            best_lag = lag;
        }
    }
    if best_lag == 0 || energy0 < 1e-9 {
        return (None, 0.0, None);
    }
    let bpm = 60.0 * frame_rate / best_lag as f32;
    let confidence = (best_val / energy0).clamp(0.0, 1.0);
    if confidence < 0.05 {
        return (None, confidence, None);
    }
    let search_end = (frame_rate * 6.0).floor() as usize;
    let mut peak_i = 0usize;
    let mut peak_v = 0.0f32;
    for (i, &v) in onset.iter().take(search_end.min(onset.len())).enumerate() {
        if v > peak_v {
            peak_i = i;
            peak_v = v;
        }
    }
    (Some(bpm), confidence, Some(peak_i as f32 / frame_rate))
}

/// 谱重心：取曲中中段的 N 帧 FFT，频率加权均值。
/// 不做 hann 窗也行 —— 我们要的是粗略亮度，不是 mastering 级精度。
fn spectral_centroid(samples: &[f32], sr: u32) -> f32 {
    const FFT_SIZE: usize = 2048;
    if samples.len() < FFT_SIZE * 4 {
        return 0.0;
    }
    // 取曲子中间 1/3 区域采样，避免开头静音 / 结尾 outro 拉偏
    let mid = samples.len() / 2;
    let win_start = mid.saturating_sub(FFT_SIZE * 8);
    let win_end = (mid + FFT_SIZE * 8).min(samples.len());

    let mut acc_num = 0.0f64;
    let mut acc_den = 0.0f64;
    let frames = (win_end - win_start) / FFT_SIZE;
    for f in 0..frames {
        let start = win_start + f * FFT_SIZE;
        let frame = &samples[start..start + FFT_SIZE];
        let bins = fft_mag_dft(frame);
        let bin_hz = sr as f32 / FFT_SIZE as f32;
        for (i, &mag) in bins.iter().enumerate().skip(1) {
            let hz = (i as f32) * bin_hz;
            acc_num += (mag as f64) * (hz as f64);
            acc_den += mag as f64;
        }
    }
    if acc_den > 1e-9 {
        (acc_num / acc_den) as f32
    } else {
        0.0
    }
}

fn estimate_tonal_key(samples: &[f32], sr: u32) -> (Option<i32>, f32) {
    const FFT_SIZE: usize = 2048;
    if samples.len() < FFT_SIZE * 8 {
        return (None, 0.0);
    }
    let start = samples.len() / 4;
    let end = (samples.len() * 3 / 4).min(samples.len());
    let frames_available = (end - start) / FFT_SIZE;
    if frames_available == 0 {
        return (None, 0.0);
    }
    let step = (frames_available / 12).max(1);
    let mut chroma = [0.0f64; 12];
    let mut used = 0usize;
    for f in (0..frames_available).step_by(step).take(16) {
        let frame_start = start + f * FFT_SIZE;
        let bins = fft_mag_dft(&samples[frame_start..frame_start + FFT_SIZE]);
        let bin_hz = sr as f32 / FFT_SIZE as f32;
        for (i, &mag) in bins.iter().enumerate().skip(5) {
            let hz = i as f32 * bin_hz;
            if !(55.0..=5000.0).contains(&hz) {
                continue;
            }
            let midi = 69.0 + 12.0 * (hz / 440.0).log2();
            let pc = ((midi.round() as i32).rem_euclid(12)) as usize;
            // 低频基音更可信，高频泛音弱化。
            let weight = (1.0 / (1.0 + hz / 1600.0)) as f64;
            chroma[pc] += mag as f64 * weight;
        }
        used += 1;
    }
    if used == 0 {
        return (None, 0.0);
    }
    let total: f64 = chroma.iter().sum();
    if total <= 1e-9 {
        return (None, 0.0);
    }
    let mut indexed: Vec<(usize, f64)> = chroma.iter().copied().enumerate().collect();
    indexed.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    let best = indexed[0];
    let second = indexed.get(1).map(|(_, v)| *v).unwrap_or(0.0);
    let confidence = ((best.1 - second) / total).clamp(0.0, 1.0) as f32;
    if confidence < 0.015 {
        (None, confidence)
    } else {
        (Some(best.0 as i32), confidence)
    }
}

/// 朴素 DFT，FFT_SIZE=2048 时 ~4M 乘加，调用一次开销 ~10-20ms。
/// 对几帧合适；要对全曲做才换 rustfft。
fn fft_mag_dft(input: &[f32]) -> Vec<f32> {
    let n = input.len();
    let half = n / 2;
    let mut out = Vec::with_capacity(half);
    let two_pi = std::f32::consts::TAU;
    for k in 0..half {
        let mut re = 0.0f32;
        let mut im = 0.0f32;
        let coef = two_pi * (k as f32) / (n as f32);
        for (j, &x) in input.iter().enumerate() {
            let angle = coef * (j as f32);
            re += x * angle.cos();
            im -= x * angle.sin();
        }
        out.push((re * re + im * im).sqrt());
    }
    out
}
