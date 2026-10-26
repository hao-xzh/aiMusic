import type { PwaTrack } from "./pwa-library";

// 原创短音序，只用于验证跨曲；不代表真实长歌曲或后台性能。
export function createDemoTracks(): PwaTrack[] {
  const sampleRate = 22050;
  const seconds = 18;
  return ["晨光", "微风", "归途"].map((name, trackIndex) => {
    const frames = sampleRate * seconds;
    const buffer = new ArrayBuffer(44 + frames * 2);
    const view = new DataView(buffer);
    const write = (offset: number, text: string) => {
      for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i));
    };
    write(0, "RIFF"); view.setUint32(4, 36 + frames * 2, true);
    write(8, "WAVEfmt "); view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    write(36, "data"); view.setUint32(40, frames * 2, true);
    const notes = [0, 4, 7, 11, 7, 4, 2, 7];
    for (let i = 0; i < frames; i++) {
      const t = i / sampleRate;
      const beat = t % 0.75;
      const frequency = 220 * 2 ** ((notes[Math.floor(t / 0.75) % notes.length] + trackIndex * 3) / 12);
      const envelope = Math.min(1, beat * 40) * Math.exp(-beat * 5) * Math.min(1, (seconds - t) * 2);
      const sample = (Math.sin(2 * Math.PI * frequency * t) + 0.18 * Math.sin(4 * Math.PI * frequency * t)) * envelope * 0.15;
      view.setInt16(44 + i * 2, sample * 32767, true);
    }
    const blob = new Blob([buffer], { type: "audio/wav" });
    return {
      id: `pipo-demo-${trackIndex + 1}`, name: `${name} · 测试音序`, blob,
      size: blob.size, mime: blob.type, addedAt: Date.now() + trackIndex,
      lrc: `[00:00.00]${name} · 原创测试音序\n[00:04.00]现在可以拖动进度\n[00:08.00]也可以点击这一行跳转\n[00:12.00]18 秒后自动播放下一段\n[00:16.00]下一段即将开始`,
    };
  });
}
