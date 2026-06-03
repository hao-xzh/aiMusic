use anyhow::{anyhow, Context, Result};
use serde::Serialize;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use symphonia::core::audio::{AudioBufferRef, Signal};
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_NULL};
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

const OUTPUT_SAMPLE_RATE: u32 = 44_100;
const OUTPUT_CHANNELS: u16 = 2;
const BITS_PER_SAMPLE: u16 = 16;
const MIN_NEXT_TEMPO_SCALE: f32 = 0.965;
const MAX_NEXT_TEMPO_SCALE: f32 = 1.035;
const WSOLA_WINDOW_FRAMES: usize = 1024;
const WSOLA_HOP_FRAMES: usize = 256;
const WSOLA_SEARCH_FRAMES: isize = 96;
const WSOLA_DIRECT_SCALE_DELTA: f32 = 0.002;
// 交叉淡变曲线见下方混音循环:等功率(constant-power)cos/sin,无需 handoff 区间 / ducking 参数。

#[derive(Debug, Clone)]
pub struct TransitionClipSpec {
    pub current_path: PathBuf,
    pub next_path: PathBuf,
    pub output_path: PathBuf,
    pub current_duration_ms: i64,
    pub mix_ms: i64,
    pub next_start_position_ms: i64,
    pub next_tempo_scale: f32,
    /// 响度对齐:渲染前施加到当前曲尾的线性增益(衰减式,≤1)。
    pub current_gain: f32,
    /// 响度对齐:渲染前施加到下一曲头的线性增益(衰减式,≤1)。
    pub next_gain: f32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransitionClipOutput {
    pub path: String,
    pub uri: String,
    pub duration_ms: i64,
    pub next_resume_position_ms: i64,
    pub sample_rate: u32,
    pub channels: u16,
}

pub fn build_transition_clip(spec: TransitionClipSpec) -> Result<TransitionClipOutput> {
    if spec.mix_ms < 650 || spec.mix_ms > 8_000 {
        return Err(anyhow!("invalid transition mix_ms {}", spec.mix_ms));
    }
    let frame_count = frames_for_ms(spec.mix_ms).max(1);
    let current = decode_stereo_to_rate(&spec.current_path, OUTPUT_SAMPLE_RATE)
        .with_context(|| format!("decode current {}", spec.current_path.display()))?;
    let next = decode_stereo_to_rate(&spec.next_path, OUTPUT_SAMPLE_RATE)
        .with_context(|| format!("decode next {}", spec.next_path.display()))?;
    if current.is_empty() || next.is_empty() {
        return Err(anyhow!("empty decoded audio"));
    }

    let current_start_ms = (spec.current_duration_ms - spec.mix_ms).max(0);
    let current_tail = segment_with_padding(&current, current_start_ms, frame_count);
    let next_scale = spec
        .next_tempo_scale
        .clamp(MIN_NEXT_TEMPO_SCALE, MAX_NEXT_TEMPO_SCALE);
    let next_segment = segment_with_padding_time_stretched(
        &next,
        spec.next_start_position_ms.max(0),
        frame_count,
        next_scale,
    );
    let next_head = next_segment.samples;
    // 响度对齐:渲染前把两轨各自压到统一目标响度(衰减式,gain≤1)。与整轨 LoudnessGainProcessor
    // 用同一套 rmsDb→gain,保证 clip 段与前后单轨段响度连续、且叠加时两首齐平,不会一首盖过另一首。
    let current_gain = spec.current_gain.clamp(0.05, 1.0);
    let next_gain = spec.next_gain.clamp(0.05, 1.0);
    let mut mixed = Vec::with_capacity(frame_count);
    let denom = (frame_count.saturating_sub(1)).max(1) as f32;
    for i in 0..frame_count {
        let p = (i as f32 / denom).clamp(0.0, 1.0);
        // 等功率(constant-power)交叉淡变:out = cos(θ)、in = sin(θ),θ ∈ [0, π/2]。
        // cos²+sin² = 1 → 过渡全程两轨功率之和恒定,中点不塌腰。旧的 smoothstep 线性叠加
        // 在中点会掉 -3~-6dB,正是"切歌时有个音量凹陷、听不出无缝"的根因。等功率是
        // Apple Music / 专业 DAW crossfade 的标准曲线;叠加段的鼓点/调性已由上游 WSOLA
        // beatmatch 对齐,因此全程交叉也不会浑浊。
        let theta = p * std::f32::consts::FRAC_PI_2;
        let out_gain = theta.cos();
        let in_gain = theta.sin();
        let a = current_tail[i];
        let b = next_head[i];
        mixed.push([
            soft_limit(a[0] * current_gain * out_gain + b[0] * next_gain * in_gain),
            soft_limit(a[1] * current_gain * out_gain + b[1] * next_gain * in_gain),
        ]);
    }
    normalize_peak(&mut mixed, 0.98);
    if let Some(parent) = spec.output_path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("create transition dir {}", parent.display()))?;
    }
    write_wav16(&spec.output_path, &mixed, OUTPUT_SAMPLE_RATE)?;
    let next_resume_position_ms =
        spec.next_start_position_ms.max(0) + ms_for_frames(next_segment.consumed_frames);
    Ok(TransitionClipOutput {
        path: spec.output_path.display().to_string(),
        uri: format!("file://{}", spec.output_path.display()),
        duration_ms: spec.mix_ms,
        next_resume_position_ms,
        sample_rate: OUTPUT_SAMPLE_RATE,
        channels: OUTPUT_CHANNELS,
    })
}

fn decode_stereo_to_rate(path: &Path, target_sr: u32) -> Result<Vec<[f32; 2]>> {
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
    let mut stereo = Vec::new();
    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
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
            Err(symphonia::core::errors::Error::DecodeError(_)) => continue,
            Err(e) => return Err(anyhow!("symphonia decode: {e}")),
        };
        append_stereo(decoded, &mut stereo);
    }
    if src_sr == target_sr {
        Ok(stereo)
    } else {
        Ok(resample_stereo_linear(&stereo, src_sr, target_sr))
    }
}

fn append_stereo(buf: AudioBufferRef<'_>, out: &mut Vec<[f32; 2]>) {
    match buf {
        AudioBufferRef::F32(b) => append_with_conv(&b, out, |s| s),
        AudioBufferRef::F64(b) => append_with_conv(&b, out, |s| s as f32),
        AudioBufferRef::S8(b) => append_with_conv(&b, out, |s| s as f32 / i8::MAX as f32),
        AudioBufferRef::S16(b) => append_with_conv(&b, out, |s| s as f32 / i16::MAX as f32),
        AudioBufferRef::S24(b) => append_with_conv(&b, out, |s| s.0 as f32 / 8_388_607.0),
        AudioBufferRef::S32(b) => append_with_conv(&b, out, |s| s as f32 / i32::MAX as f32),
        AudioBufferRef::U8(b) => append_with_conv(&b, out, |s| (s as f32 - 128.0) / 128.0),
        AudioBufferRef::U16(b) => append_with_conv(&b, out, |s| (s as f32 - 32_768.0) / 32_768.0),
        AudioBufferRef::U24(b) => {
            append_with_conv(&b, out, |s| (s.0 as f32 - 8_388_608.0) / 8_388_608.0)
        }
        AudioBufferRef::U32(b) => {
            append_with_conv(&b, out, |s| (s as f32 - 2_147_483_648.0) / 2_147_483_648.0)
        }
    }
}

fn append_with_conv<S: symphonia::core::sample::Sample + Copy>(
    buf: &symphonia::core::audio::AudioBuffer<S>,
    out: &mut Vec<[f32; 2]>,
    conv: impl Fn(S) -> f32,
) {
    let frames = buf.frames();
    let chans = buf.spec().channels.count().max(1);
    out.reserve(frames);
    for f in 0..frames {
        let left = conv(buf.chan(0)[f]);
        let right = if chans > 1 {
            conv(buf.chan(1)[f])
        } else {
            left
        };
        out.push([left, right]);
    }
}

fn resample_stereo_linear(input: &[[f32; 2]], src_sr: u32, dst_sr: u32) -> Vec<[f32; 2]> {
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
        out.push([a[0] + (b[0] - a[0]) * frac, a[1] + (b[1] - a[1]) * frac]);
    }
    out
}

fn segment_with_padding(samples: &[[f32; 2]], start_ms: i64, frame_count: usize) -> Vec<[f32; 2]> {
    let start = frames_for_ms(start_ms.max(0));
    let mut out = Vec::with_capacity(frame_count);
    for i in 0..frame_count {
        out.push(samples.get(start + i).copied().unwrap_or([0.0, 0.0]));
    }
    out
}

struct TimeStretchSegment {
    samples: Vec<[f32; 2]>,
    consumed_frames: usize,
}

fn segment_with_padding_time_stretched(
    samples: &[[f32; 2]],
    start_ms: i64,
    frame_count: usize,
    source_scale: f32,
) -> TimeStretchSegment {
    let scale = source_scale.clamp(MIN_NEXT_TEMPO_SCALE, MAX_NEXT_TEMPO_SCALE);
    let start = frames_for_ms(start_ms.max(0));
    let expected_consumed = ((frame_count as f32) * scale).round().max(0.0) as usize;
    if frame_count == 0 {
        return TimeStretchSegment {
            samples: Vec::new(),
            consumed_frames: 0,
        };
    }
    if (scale - 1.0).abs() < WSOLA_DIRECT_SCALE_DELTA {
        return TimeStretchSegment {
            samples: segment_with_padding(samples, start_ms, frame_count),
            consumed_frames: frame_count,
        };
    }

    let window = WSOLA_WINDOW_FRAMES.min(frame_count).max(1);
    let hop = WSOLA_HOP_FRAMES.min(window).max(1);
    let window_weights: Vec<f32> = (0..window)
        .map(|i| stretch_window_weight(i, window))
        .collect();
    let mut accum = vec![[0.0_f32, 0.0_f32]; frame_count];
    let mut weights = vec![0.0_f32; frame_count];
    let mut out_pos = 0usize;
    let mut consumed_frames = expected_consumed;

    while out_pos < frame_count {
        let target_pos = start as f32 + out_pos as f32 * scale;
        let src_pos = if out_pos == 0 {
            start as isize
        } else {
            best_wsola_source_position(samples, &accum, &weights, out_pos, target_pos, window, hop)
        };
        let copy_len = (frame_count - out_pos).min(window);
        for j in 0..copy_len {
            let out_idx = out_pos + j;
            let src_idx = (src_pos + j as isize).max(0) as usize;
            let sample = samples.get(src_idx).copied().unwrap_or([0.0, 0.0]);
            let weight = window_weights[j];
            accum[out_idx][0] += sample[0] * weight;
            accum[out_idx][1] += sample[1] * weight;
            weights[out_idx] += weight;
            if src_idx >= start && src_idx < samples.len() {
                consumed_frames = consumed_frames.max(src_idx - start + 1);
            }
        }
        if out_pos + hop >= frame_count {
            break;
        }
        out_pos += hop;
    }

    let mut out = Vec::with_capacity(frame_count);
    for i in 0..frame_count {
        let weight = weights[i];
        if weight > 0.000_001 {
            out.push([accum[i][0] / weight, accum[i][1] / weight]);
        } else {
            out.push(sample_linear(samples, start as f32 + i as f32 * scale));
        }
    }
    TimeStretchSegment {
        samples: out,
        consumed_frames,
    }
}

fn best_wsola_source_position(
    samples: &[[f32; 2]],
    accum: &[[f32; 2]],
    weights: &[f32],
    out_pos: usize,
    target_pos: f32,
    window: usize,
    hop: usize,
) -> isize {
    let overlap = window
        .saturating_sub(hop)
        .min(out_pos)
        .min(accum.len() - out_pos);
    if overlap < 64 {
        return target_pos.round() as isize;
    }
    let target = target_pos.round() as isize;
    let mut best = target;
    let mut best_score = f32::NEG_INFINITY;
    for offset in -WSOLA_SEARCH_FRAMES..=WSOLA_SEARCH_FRAMES {
        let candidate = target + offset;
        let score = overlap_correlation(samples, accum, weights, out_pos, candidate, overlap)
            - (offset.unsigned_abs() as f32 * 0.000_015);
        if score > best_score {
            best_score = score;
            best = candidate;
        }
    }
    best
}

fn overlap_correlation(
    samples: &[[f32; 2]],
    accum: &[[f32; 2]],
    weights: &[f32],
    out_pos: usize,
    candidate_pos: isize,
    overlap: usize,
) -> f32 {
    let mut dot = 0.0_f32;
    let mut a2 = 0.0_f32;
    let mut b2 = 0.0_f32;
    let mut n = 0usize;
    for j in 0..overlap {
        let out_idx = out_pos + j;
        let weight = weights[out_idx];
        if weight <= 0.000_001 {
            continue;
        }
        let src_idx = candidate_pos + j as isize;
        let b = if src_idx >= 0 {
            samples.get(src_idx as usize).copied().unwrap_or([0.0, 0.0])
        } else {
            [0.0, 0.0]
        };
        let a_mono = ((accum[out_idx][0] / weight) + (accum[out_idx][1] / weight)) * 0.5;
        let b_mono = (b[0] + b[1]) * 0.5;
        dot += a_mono * b_mono;
        a2 += a_mono * a_mono;
        b2 += b_mono * b_mono;
        n += 1;
    }
    if n < 32 || a2 <= 0.000_000_1 || b2 <= 0.000_000_1 {
        return f32::NEG_INFINITY;
    }
    dot / (a2.sqrt() * b2.sqrt()).max(0.000_001)
}

fn stretch_window_weight(i: usize, window: usize) -> f32 {
    if window <= 1 {
        return 1.0;
    }
    let phase = (i as f32 / (window - 1) as f32) * std::f32::consts::TAU;
    0.08 + 0.92 * (0.5 - 0.5 * phase.cos())
}

fn sample_linear(samples: &[[f32; 2]], pos: f32) -> [f32; 2] {
    if samples.is_empty() {
        return [0.0, 0.0];
    }
    let idx = pos.floor().max(0.0) as usize;
    let frac = (pos - idx as f32).clamp(0.0, 1.0);
    let a = samples.get(idx).copied().unwrap_or([0.0, 0.0]);
    let b = samples.get(idx + 1).copied().unwrap_or(a);
    [a[0] + (b[0] - a[0]) * frac, a[1] + (b[1] - a[1]) * frac]
}

fn frames_for_ms(ms: i64) -> usize {
    ((ms.max(0) as u64 * OUTPUT_SAMPLE_RATE as u64) / 1000) as usize
}

fn ms_for_frames(frames: usize) -> i64 {
    (((frames as u64 * 1000) + (OUTPUT_SAMPLE_RATE as u64 / 2)) / OUTPUT_SAMPLE_RATE as u64) as i64
}

fn soft_limit(x: f32) -> f32 {
    x / (1.0 + 0.15 * x.abs())
}

fn normalize_peak(samples: &mut [[f32; 2]], target: f32) {
    let peak = samples
        .iter()
        .flat_map(|s| [s[0].abs(), s[1].abs()])
        .fold(0.0_f32, f32::max);
    if peak > target && peak > 0.0 {
        let scale = target / peak;
        for s in samples {
            s[0] *= scale;
            s[1] *= scale;
        }
    }
}

fn write_wav16(path: &Path, samples: &[[f32; 2]], sample_rate: u32) -> Result<()> {
    let bytes_per_sample = (BITS_PER_SAMPLE / 8) as u32;
    let data_bytes = samples.len() as u32 * OUTPUT_CHANNELS as u32 * bytes_per_sample;
    let byte_rate = sample_rate * OUTPUT_CHANNELS as u32 * bytes_per_sample;
    let block_align = OUTPUT_CHANNELS * (BITS_PER_SAMPLE / 8);
    let mut out = BufWriter::new(
        File::create(path).with_context(|| format!("create transition wav {}", path.display()))?,
    );
    out.write_all(b"RIFF")?;
    out.write_all(&(36 + data_bytes).to_le_bytes())?;
    out.write_all(b"WAVE")?;
    out.write_all(b"fmt ")?;
    out.write_all(&16_u32.to_le_bytes())?;
    out.write_all(&1_u16.to_le_bytes())?;
    out.write_all(&OUTPUT_CHANNELS.to_le_bytes())?;
    out.write_all(&sample_rate.to_le_bytes())?;
    out.write_all(&byte_rate.to_le_bytes())?;
    out.write_all(&block_align.to_le_bytes())?;
    out.write_all(&BITS_PER_SAMPLE.to_le_bytes())?;
    out.write_all(b"data")?;
    out.write_all(&data_bytes.to_le_bytes())?;
    for frame in samples {
        for channel in frame {
            let v = (channel.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16;
            out.write_all(&v.to_le_bytes())?;
        }
    }
    out.flush()?;
    Ok(())
}
