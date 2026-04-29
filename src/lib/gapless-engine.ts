"use client";

/**
 * 真 gapless 引擎 —— 解出 PCM、剥掉编码器 padding、把上一首最后一个采样接到
 * 下一首第一个采样上。零间隔、零叠化。
 *
 * 实现策略：三层 fallback，永远成功，精度逐级下降但听感差异远小于 1 ms。
 *
 *   Tier 1: 解析容器内的编码器元数据
 *           - MP3: Xing/Info 帧后的 LAME tag → enc_delay (12 bits) + enc_padding (12 bits)
 *           - MP4/M4A: moov.udta.meta.ilst.----.iTunSMPB 字符串 → field 2/3
 *           覆盖率 ~85-95%，采样级精确。
 *
 *   Tier 2: 没元数据 → 用格式标准常数
 *           - MP3:  head = 1728 samples (LAME 默认 576 编码 + 1152 解码 lookahead)
 *           - AAC:  head = 2112 samples (AAC 标准 priming)
 *           - FLAC/OGG/WAV: 0 (天生无 padding)
 *           尾部走 Tier 3。
 *
 *   Tier 3: PCM 末尾扫数字静音 (sample === 0 严格判定)
 *           encoder padding 一定是纯 0。有损编码后的"安静"音乐有底噪不会出现连续纯 0，
 *           所以这条扫描永远不会误吃音乐。
 *
 * 平台差异：WKWebView/Safari 的 AAC 解码器会按 iTunSMPB 自动 pre-trim；
 * Chromium/WebView2 不会。我们用"解码后实际 sample 数 vs 容器声明的总 sample 数"
 * 现场判定，避免双重裁剪。
 */

export type PaddingSource = "lame" | "itunsmpb" | "default" | "pcm";

export type PaddingInfo = {
  /** 头部要丢的 sample 数 (per-channel) */
  headSamples: number;
  /** 尾部要丢的 sample 数 (per-channel) */
  tailSamples: number;
  /** 信息来源 */
  source: PaddingSource;
  /** 容器声明的"trim 后"总 sample 数，用来跟解码结果比对判定平台是否已自动 trim。null 表示未知。 */
  expectedTrimmedSamples: number | null;
  /** 容器声明的"原始"总 sample 数（含 delay+padding）。null 表示未知。 */
  expectedRawSamples: number | null;
};

export type DecodeResult = {
  buffer: AudioBuffer;
  /** 实际生效的裁剪量（per-channel sample 数） */
  trimmedHead: number;
  trimmedTail: number;
  /** 元数据来源链，便于诊断 */
  meta: PaddingInfo;
  /** 解码器是否被检测到自动 pre-trim 了头部 */
  decoderAutoTrimmed: boolean;
};

type Format = "mp3" | "mp4" | "flac" | "ogg" | "wav" | "unknown";

// ============================================================
// 公共入口
// ============================================================

/**
 * 解码并裁剪 padding。返回的 AudioBuffer 第 0 个 sample 就是音乐第一个采样，
 * 最后 1 个 sample 就是音乐最后一个采样。直接拿去 schedule 即可。
 */
export async function decodeAndTrim(
  arrayBuffer: ArrayBuffer,
  ctx: BaseAudioContext,
): Promise<DecodeResult> {
  // decodeAudioData 会消化掉 ArrayBuffer (transfer)，所以解析 padding 必须在前面，
  // 而且要在副本上做（不要把 buffer 拷一份就走，那是浪费 80MB）—— 元数据只需要前 64KB。
  const headView = new Uint8Array(arrayBuffer.slice(0, Math.min(arrayBuffer.byteLength, 65536)));
  const meta = parsePadding(headView);

  const decoded = await ctx.decodeAudioData(arrayBuffer);

  return finalizeTrim(decoded, meta, ctx);
}

/**
 * 仅解析元数据，不解码。给上层做"先决定能不能 gapless"的快路径。
 */
export function parsePadding(bytes: Uint8Array): PaddingInfo {
  const fmt = detectFormat(bytes);

  if (fmt === "mp3") {
    const lame = tryParseLame(bytes);
    if (lame) return lame;
    return {
      headSamples: 1728,
      tailSamples: 0,
      source: "default",
      expectedTrimmedSamples: null,
      expectedRawSamples: null,
    };
  }

  if (fmt === "mp4") {
    const itunes = tryParseITunSMPB(bytes);
    if (itunes) return itunes;
    return {
      headSamples: 2112,
      tailSamples: 0,
      source: "default",
      expectedTrimmedSamples: null,
      expectedRawSamples: null,
    };
  }

  // FLAC / OGG(Vorbis/Opus) / WAV: 容器/编码本身没有"padding"概念，至多尾部
  // 有几个 0 sample 由 PCM 扫描兜底。
  return {
    headSamples: 0,
    tailSamples: 0,
    source: fmt === "unknown" ? "default" : "default",
    expectedTrimmedSamples: null,
    expectedRawSamples: null,
  };
}

// ============================================================
// 格式探测
// ============================================================

function detectFormat(b: Uint8Array): Format {
  if (b.length < 12) return "unknown";

  // ID3v2 标签：跳过头部找下一段
  if (b[0] === 0x49 && b[1] === 0x44 && b[2] === 0x33) {
    const id3End = id3v2Length(b);
    if (id3End < b.length - 4) {
      // 检查 ID3 后面的 sync
      if ((b[id3End] === 0xff && (b[id3End + 1]! & 0xe0) === 0xe0)) return "mp3";
    }
    return "mp3"; // ID3v2 99% 跟着 mp3
  }

  // MP3 sync（无 ID3）
  if (b[0] === 0xff && (b[1]! & 0xe0) === 0xe0) return "mp3";

  // MP4/M4A: 'ftyp' atom at offset 4
  if (b[4] === 0x66 && b[5] === 0x74 && b[6] === 0x79 && b[7] === 0x70) return "mp4";

  // FLAC magic
  if (b[0] === 0x66 && b[1] === 0x4c && b[2] === 0x61 && b[3] === 0x43) return "flac";

  // OGG magic 'OggS'
  if (b[0] === 0x4f && b[1] === 0x67 && b[2] === 0x67 && b[3] === 0x53) return "ogg";

  // WAV 'RIFF....WAVE'
  if (
    b[0] === 0x52 &&
    b[1] === 0x49 &&
    b[2] === 0x46 &&
    b[3] === 0x46 &&
    b[8] === 0x57 &&
    b[9] === 0x41 &&
    b[10] === 0x56 &&
    b[11] === 0x45
  )
    return "wav";

  return "unknown";
}

function id3v2Length(b: Uint8Array): number {
  // ID3v2 头：'ID3' + ver(2) + flags(1) + size(4 synchsafe)
  // size 是 synchsafe：每字节高位是 0，只用低 7 位
  if (b.length < 10) return 0;
  const size =
    ((b[6]! & 0x7f) << 21) |
    ((b[7]! & 0x7f) << 14) |
    ((b[8]! & 0x7f) << 7) |
    (b[9]! & 0x7f);
  const hasFooter = (b[5]! & 0x10) !== 0;
  return 10 + size + (hasFooter ? 10 : 0);
}

// ============================================================
// MP3 LAME tag 解析
// ============================================================

function tryParseLame(b: Uint8Array): PaddingInfo | null {
  // 找到第一个 MP3 frame 的位置
  let p = 0;
  if (b[0] === 0x49 && b[1] === 0x44 && b[2] === 0x33) {
    p = id3v2Length(b);
  }
  // 跳过可能的填充字节直到找到 sync
  while (p + 4 < b.length) {
    if (b[p] === 0xff && (b[p + 1]! & 0xe0) === 0xe0) break;
    p++;
  }
  if (p + 4 >= b.length) return null;

  const h0 = b[p + 1]!;
  const h1 = b[p + 2]!;
  // bits[20..19]: MPEG version. 11=MPEG1, 10=MPEG2, 00=MPEG2.5, 01=reserved
  const versionBits = (h0 >> 3) & 0x03;
  if (versionBits === 0x01) return null; // reserved
  const isMpeg1 = versionBits === 0x03;

  // bits[17..18] in h0: layer. 01=Layer3
  const layerBits = (h0 >> 1) & 0x03;
  if (layerBits !== 0x01) return null; // 不是 Layer 3，跳过

  // bits[7..6] in h1: channel mode. 11=mono
  const channelMode = (h1 >> 6) & 0x03;
  const isMono = channelMode === 0x03;

  // sample rate（用于把 delay/padding 折算回去验证）
  const srIndex = (h0 >> 2) & 0x03;
  const sampleRate = mp3SampleRate(versionBits, srIndex);
  if (!sampleRate) return null;

  // side info 长度
  let sideInfoLen: number;
  if (isMpeg1) sideInfoLen = isMono ? 17 : 32;
  else sideInfoLen = isMono ? 9 : 17;

  // Xing/Info tag 起始
  const xingStart = p + 4 + sideInfoLen;
  if (xingStart + 8 > b.length) return null;

  const tag =
    String.fromCharCode(b[xingStart]!, b[xingStart + 1]!, b[xingStart + 2]!, b[xingStart + 3]!);
  if (tag !== "Xing" && tag !== "Info") return null;

  const flags =
    (b[xingStart + 4]! << 24) |
    (b[xingStart + 5]! << 16) |
    (b[xingStart + 6]! << 8) |
    b[xingStart + 7]!;

  let cursor = xingStart + 8;
  let totalFrames: number | null = null;
  if (flags & 0x01) {
    if (cursor + 4 > b.length) return null;
    totalFrames =
      (b[cursor]! << 24) |
      (b[cursor + 1]! << 16) |
      (b[cursor + 2]! << 8) |
      b[cursor + 3]!;
    cursor += 4;
  }
  if (flags & 0x02) cursor += 4; // bytes
  if (flags & 0x04) cursor += 100; // TOC
  if (flags & 0x08) cursor += 4; // quality

  // LAME tag：36 字节，从 cursor 开始
  if (cursor + 24 > b.length) return null;

  // 校验前 4 字节是否像编码器名字（ASCII 可见字符）
  const c0 = b[cursor]!;
  const c1 = b[cursor + 1]!;
  const c2 = b[cursor + 2]!;
  const c3 = b[cursor + 3]!;
  const looksAscii = (x: number) => x >= 0x20 && x <= 0x7e;
  if (!looksAscii(c0) || !looksAscii(c1) || !looksAscii(c2) || !looksAscii(c3)) return null;

  // delay (12 bits) + padding (12 bits) 占 cursor+21..cursor+23
  const dp0 = b[cursor + 21]!;
  const dp1 = b[cursor + 22]!;
  const dp2 = b[cursor + 23]!;
  const encDelay = (dp0 << 4) | (dp1 >> 4);
  const encPadding = ((dp1 & 0x0f) << 8) | dp2;

  // 健全性检查：delay 一般 1000-3000，padding 0-1500。超过 5000 几乎肯定是垃圾位
  if (encDelay > 5000 || encPadding > 5000) return null;

  // MP3 解码器还会引入 528 + 1 = 529 samples 的固定 lookahead delay。
  // LAME tag 里的 enc_delay 是"编码侧+解码侧"总共要丢的量，不需要再加。
  // 参考：https://lame.sourceforge.io/tech-FAQ.txt

  // 算 expectedRawSamples：每个 MPEG1 Layer3 frame = 1152 samples，MPEG2/2.5 = 576
  let samplesPerFrame = isMpeg1 ? 1152 : 576;
  let expectedRawSamples: number | null = null;
  let expectedTrimmedSamples: number | null = null;
  if (totalFrames !== null) {
    expectedRawSamples = totalFrames * samplesPerFrame;
    expectedTrimmedSamples = expectedRawSamples - encDelay - encPadding;
  }

  return {
    headSamples: encDelay,
    tailSamples: encPadding,
    source: "lame",
    expectedTrimmedSamples,
    expectedRawSamples,
  };
}

function mp3SampleRate(versionBits: number, srIndex: number): number | null {
  if (srIndex === 0x03) return null;
  const table: Record<number, number[]> = {
    0x03: [44100, 48000, 32000], // MPEG1
    0x02: [22050, 24000, 16000], // MPEG2
    0x00: [11025, 12000, 8000], // MPEG2.5
  };
  const row = table[versionBits];
  return row ? row[srIndex] ?? null : null;
}

// ============================================================
// MP4/M4A iTunSMPB 解析
// ============================================================

function tryParseITunSMPB(b: Uint8Array): PaddingInfo | null {
  // 走完整的 atom 树：moov → udta → meta → ilst → ---- → name + data
  const moov = findChildAtom(b, 0, b.length, "moov");
  if (!moov) return null;
  const udta = findChildAtom(b, moov.contentStart, moov.contentEnd, "udta");
  if (!udta) return null;
  const meta = findChildAtom(b, udta.contentStart, udta.contentEnd, "meta");
  if (!meta) return null;
  // meta 比较特殊：type 后面有 4 字节 version/flags 才是子 atom
  const ilst = findChildAtom(b, meta.contentStart + 4, meta.contentEnd, "ilst");
  if (!ilst) return null;

  let smpbValue: string | null = null;
  // 遍历 ilst 下面所有 ---- atom
  let cursor = ilst.contentStart;
  while (cursor + 8 <= ilst.contentEnd) {
    const atom = readAtom(b, cursor, ilst.contentEnd);
    if (!atom) break;
    if (atom.type === "----") {
      // 在 ---- 内部找 name 和 data
      let name: string | null = null;
      let dataStr: string | null = null;
      let inner = atom.contentStart;
      while (inner + 8 <= atom.contentEnd) {
        const sub = readAtom(b, inner, atom.contentEnd);
        if (!sub) break;
        if (sub.type === "name") {
          // name payload: 4 bytes version/flags + UTF-8
          const nameStart = sub.contentStart + 4;
          name = utf8(b, nameStart, sub.contentEnd);
        } else if (sub.type === "data") {
          // data payload: 4 bytes type + 4 bytes locale + value
          const dataStart = sub.contentStart + 8;
          dataStr = utf8(b, dataStart, sub.contentEnd);
        }
        inner = sub.next;
      }
      if (name === "iTunSMPB" && dataStr) {
        smpbValue = dataStr;
        break;
      }
    }
    cursor = atom.next;
  }

  if (!smpbValue) return null;

  // iTunSMPB 字符串：" 00000000 00000840 0000037A 00000000007E76C6 ... "
  // 前导空格 + 9 个十六进制字段。field 1=零，field 2=delay，field 3=padding，
  // field 4=trim 后总样本数（16 hex chars），field 5..9=保留。
  const parts = smpbValue.trim().split(/\s+/);
  if (parts.length < 4) return null;
  const delay = parseInt(parts[1]!, 16);
  const padding = parseInt(parts[2]!, 16);
  const trimmedSamples = parseInt(parts[3]!, 16);
  if (!Number.isFinite(delay) || !Number.isFinite(padding)) return null;
  if (delay > 10000 || padding > 10000) return null;

  return {
    headSamples: delay,
    tailSamples: padding,
    source: "itunsmpb",
    expectedTrimmedSamples: Number.isFinite(trimmedSamples) ? trimmedSamples : null,
    expectedRawSamples:
      Number.isFinite(trimmedSamples) ? trimmedSamples + delay + padding : null,
  };
}

type Atom = {
  type: string;
  /** 包含 8 字节 header 的开始位置 */
  start: number;
  /** payload 起点 */
  contentStart: number;
  /** payload 终点（exclusive） */
  contentEnd: number;
  /** 下一个 sibling atom 的起点 */
  next: number;
};

function readAtom(b: Uint8Array, start: number, hardEnd: number): Atom | null {
  if (start + 8 > hardEnd) return null;
  let size =
    (b[start]! << 24) | (b[start + 1]! << 16) | (b[start + 2]! << 8) | b[start + 3]!;
  // 用无符号
  size = size >>> 0;
  const type = String.fromCharCode(
    b[start + 4]!,
    b[start + 5]!,
    b[start + 6]!,
    b[start + 7]!,
  );
  let contentStart = start + 8;
  let totalSize = size;
  if (size === 1) {
    // 64-bit ext size：很少见，但要支持
    if (start + 16 > hardEnd) return null;
    // 高 32 位通常是 0；只取低 32 位防爆
    const low =
      (b[start + 12]! << 24) |
      (b[start + 13]! << 16) |
      (b[start + 14]! << 8) |
      b[start + 15]!;
    totalSize = low >>> 0;
    contentStart = start + 16;
  } else if (size === 0) {
    // size 0 表示"延伸到文件末"
    totalSize = hardEnd - start;
  }
  const contentEnd = Math.min(start + totalSize, hardEnd);
  const next = start + totalSize;
  if (totalSize < 8) return null; // 异常
  return { type, start, contentStart, contentEnd, next };
}

function findChildAtom(
  b: Uint8Array,
  start: number,
  end: number,
  type: string,
): Atom | null {
  let cursor = start;
  while (cursor + 8 <= end) {
    const atom = readAtom(b, cursor, end);
    if (!atom) return null;
    if (atom.type === type) return atom;
    cursor = atom.next;
    if (cursor <= atom.start) return null; // 防死循环
  }
  return null;
}

function utf8(b: Uint8Array, start: number, end: number): string {
  // 直接 TextDecoder 比手写 UTF-8 安全
  return new TextDecoder("utf-8").decode(b.subarray(start, end));
}

// ============================================================
// PCM 末尾数字静音扫描
// ============================================================

/**
 * 从尾部往前扫，统计所有声道都恰好为 0 的连续 sample 数。
 *
 * Encoder padding 写出来一定是纯 0 (frame zero-fill)。有损解码后的"安静"音乐
 * 仍有 dither/底噪 (≥ 1e-5)，几乎不可能连续 8 个采样恰好为 0。
 * 阈值取 8 是为了排除偶然，同时保证 padding 区域（一般 100+ samples）能完整识别。
 */
export function scanTrailingDigitalSilence(buf: AudioBuffer, minRun = 8): number {
  const channels = buf.numberOfChannels;
  const length = buf.length;
  const datas: Float32Array[] = [];
  for (let c = 0; c < channels; c++) datas.push(buf.getChannelData(c));

  let zeros = 0;
  for (let i = length - 1; i >= 0; i--) {
    let allZero = true;
    for (let c = 0; c < channels; c++) {
      if (datas[c]![i] !== 0) {
        allZero = false;
        break;
      }
    }
    if (!allZero) break;
    zeros++;
  }
  return zeros >= minRun ? zeros : 0;
}

// ============================================================
// AudioBuffer 裁剪
// ============================================================

export function trimBuffer(
  src: AudioBuffer,
  headSamples: number,
  tailSamples: number,
  ctx: BaseAudioContext,
): AudioBuffer {
  const head = Math.max(0, Math.floor(headSamples));
  const tail = Math.max(0, Math.floor(tailSamples));
  const newLength = Math.max(0, src.length - head - tail);
  if (newLength === src.length) return src;

  const out = ctx.createBuffer(src.numberOfChannels, newLength, src.sampleRate);
  for (let c = 0; c < src.numberOfChannels; c++) {
    const srcData = src.getChannelData(c);
    const dst = out.getChannelData(c);
    // 用 subarray + set 比一格一格 copy 快一个量级
    dst.set(srcData.subarray(head, head + newLength));
  }
  return out;
}

// ============================================================
// 平台 auto-trim 检测 + 最终裁剪
// ============================================================

function finalizeTrim(
  decoded: AudioBuffer,
  meta: PaddingInfo,
  ctx: BaseAudioContext,
): DecodeResult {
  // 平台是否已经替我们 trim 了？比对解码长度和容器声明长度。
  // 容差 ±64 samples 吸收 frame 对齐误差。
  let decoderAutoTrimmed = false;
  if (
    meta.expectedTrimmedSamples !== null &&
    meta.expectedRawSamples !== null &&
    meta.headSamples + meta.tailSamples > 64
  ) {
    const distToTrimmed = Math.abs(decoded.length - meta.expectedTrimmedSamples);
    const distToRaw = Math.abs(decoded.length - meta.expectedRawSamples);
    if (distToTrimmed < distToRaw && distToTrimmed < 64) {
      decoderAutoTrimmed = true;
    }
  }

  let head = decoderAutoTrimmed ? 0 : meta.headSamples;
  let tail = decoderAutoTrimmed ? 0 : meta.tailSamples;

  // Tier 3 兜底：尾部数字静音扫描。即使 metadata 给了 tail 值，也用 PCM 扫描取
  // max，因为某些 transcode 流程会让实际 padding 比声明的更长。
  const pcmTail = scanTrailingDigitalSilence(decoded);
  if (pcmTail > tail) tail = pcmTail;

  const buffer = trimBuffer(decoded, head, tail, ctx);
  return {
    buffer,
    trimmedHead: head,
    trimmedTail: tail,
    meta,
    decoderAutoTrimmed,
  };
}
