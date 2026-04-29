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
    /// 谱重心（Hz）—— 音色亮度的代理。男声唱大约 1500，女声 2500，金属 4000+
    pub spectral_centroid_hz: f32,
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
    let (bpm, bpm_conf) = detect_bpm(&lowband);

    let spectral_centroid_hz = spectral_centroid(&samples, TARGET_SR);
    let (head_silence_s, tail_silence_s) = silence_boundaries(&samples, TARGET_SR);

    Ok(Acoustics {
        track_id,
        duration_s,
        bpm,
        bpm_confidence: bpm_conf,
        rms_db,
        peak_db,
        dynamic_range_db: peak_db - rms_db,
        intro_energy,
        outro_energy,
        spectral_centroid_hz,
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
    (head_frames as f32 * frame_dur, tail_frames as f32 * frame_dur)
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
        AudioBufferRef::U32(b) => mix_with_conv(&b, out, |s| (s as f32 - 2147483648.0) / 2147483648.0),
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
        let b = if idx + 1 < input.len() { input[idx + 1] } else { a };
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

/// BPM 检测：能量包络（hop=256, ~86 fps）→ 高通去 DC → 自相关在 60-200 BPM 找峰。
///
/// 返回 (bpm, confidence)。confidence 是主峰高度归一化到 0..1，0.3 以上算可信。
fn detect_bpm(lowband: &[f32]) -> (Option<f32>, f32) {
    const HOP: usize = 256;
    let frame_count = lowband.len() / HOP;
    if frame_count < 64 {
        return (None, 0.0);
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
        return (None, 0.0);
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
        return (None, 0.0);
    }
    let bpm = 60.0 * frame_rate / best_lag as f32;
    let confidence = (best_val / energy0).clamp(0.0, 1.0);
    if confidence < 0.05 {
        return (None, confidence);
    }
    (Some(bpm), confidence)
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

