"use client";

import { useEffect, useState } from "react";
import { artworkUrl } from "./api";

// Runtime artwork palette algorithm ported from Android CoverColor.kt (d1d8128).
export type RGB = [number, number, number];
export type Palette = Partial<Record<"top" | "bottom" | "left" | "right" | "lower" | "seam" | "accent" | "ambient" | "rightUpper" | "rightMiddle" | "rightLower", RGB | null>>;
const cache = new Map<string, Palette>();
const inflight = new Map<string, Promise<Palette>>();
const empty: Palette = {};
export const clamp = (value: number, low: number, high: number) => Math.max(low, Math.min(high, value));
export const luma = (rgb?: RGB | null) => rgb ? .299 * rgb[0] + .587 * rgb[1] + .114 * rgb[2] : 128;
export const cssRgb = (rgb: RGB, alpha?: number) => alpha == null ? `rgb(${rgb.join(",")})` : `rgba(${rgb.join(",")},${alpha})`;
export function blend(a: RGB | null | undefined, b: RGB | null | undefined, t: number): RGB | null {
  if (!a) return b || null; if (!b) return a;
  return a.map((value, i) => Math.trunc(clamp(value + (b[i] - value) * clamp(t, 0, 1), 0, 255))) as RGB;
}
function hsv(rgb: RGB): RGB {
  const [r, g, b] = rgb.map(value => value / 255); const max = Math.max(r, g, b); const min = Math.min(r, g, b); const d = max - min;
  const hue = !d ? 0 : max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [(hue * 60 + 360) % 360, max ? d / max : 0, max];
}
function rgb([h, s, v]: RGB): RGB {
  const c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
  const base = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return base.map(value => Math.round((value + m) * 255)) as RGB;
}
export function lyricAccent(value?: RGB | null): RGB | null {
  if (!value) return null; const [h, s, v] = hsv(value); return rgb([h, clamp(s * 1.15, .55, 1), clamp(v, .7, .92)]);
}
function normalize(value: RGB | null, bridge = false, landscape = false): RGB {
  if (!value) return [10, 13, 20];
  const [h, s, v] = hsv(value); const light = luma(value);
  if (landscape) return light > 180 ? rgb([h, clamp(s * .92, 0, .36), clamp(v, .82, .97)]) : light > 120 ? rgb([h, clamp(s * .98, 0, .5), clamp(v, .56, .84)]) : rgb([h, clamp(s * .96, 0, .74), clamp(v, .04, .48)]);
  if (bridge) return light > 180 ? rgb([h, clamp(s * .94, .035, .38), clamp(v, .84, .97)]) : light > 120 ? rgb([h, clamp(s * .98, .08, .5), clamp(v, .58, .86)]) : rgb([h, clamp(s * 1.02, .14, .76), clamp(v, .20, .46)]);
  return light > 180 ? rgb([h, clamp(s * .92, .035, .34), clamp(v, .84, .97)]) : light > 120 ? rgb([h, clamp(s * .98, .08, .44), clamp(v, .56, .82)]) : rgb([h, clamp(s * 1.08, .16, .74), clamp(v, .07, .23)]);
}
export function paletteSurfaces(p: Palette) {
  const base = p.seam || p.lower || p.bottom || p.ambient || p.top || p.right || p.left;
  const light = luma(base);
  const surface = normalize(blend(base, light > 120 ? p.accent || p.ambient : p.lower || p.bottom || p.ambient, light > 180 ? .14 : .08));
  const seam = p.seam || p.lower || p.bottom || p.ambient;
  const top = normalize(blend(p.top || p.ambient || seam, seam, .18));
  const bridge = normalize(blend(base, p.accent || p.ambient, .06), true);
  const wash = normalize(blend(base, p.accent || p.top || p.right || p.ambient || base, .18), true);
  const side = p.right || p.ambient || base;
  const landscape = normalize(blend(side, p.ambient || p.accent, luma(side) > 170 ? .10 : .06), false, true);
  return { surface, top, bridge, wash, landscape };
}
export function browseSurfaces(p: Palette) {
  const colors = paletteSurfaces(p); const bg: RGB = [7, 11, 18];
  return { surface: blend(colors.surface, bg, .88)!, bridge: blend(colors.bridge, bg, .8)!, top: blend(colors.top, bg, .66)! };
}
export function miniPalette(p: Palette) {
  const base = p.accent || p.ambient || p.seam;
  const surface: RGB = base ? blend(base, [0, 0, 0], .68)! : [16, 21, 27];
  let ink: RGB = lyricAccent(base) || [243, 245, 247];
  const luminance = (value: RGB) => value.map(c => { const v = c / 255; return v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }).reduce((sum, value, i) => sum + value * [.2126, .7152, .0722][i], 0);
  for (let i = 0; i < 36 && (luminance(ink) + .05) / (luminance(surface) + .05) < 4.5; i++) ink = ink.map(c => Math.round(c + (255 - c) * .12)) as RGB;
  return { surface, ink };
}

function sample(data: Uint8ClampedArray): Palette {
  type Edge = "top" | "bottom" | "left" | "right" | "lower" | "seam";
  const dominant = (ys: number, ye: number, xs = 0, xe = 48, edge?: Edge): RGB | null => {
    const weights = new Float64Array(4096), rs = new Float64Array(4096), gs = new Float64Array(4096), bs = new Float64Array(4096);
    for (let y = ys; y < ye; y++) for (let x = xs; x < xe; x++) {
      const i = (y * 48 + x) * 4; if (data[i + 3] < 16) continue;
      const r = data[i], g = data[i + 1], b = data[i + 2], light = .299 * r + .587 * g + .114 * b, chroma = Math.max(r, g, b) - Math.min(r, g, b);
      const distance = edge === "top" ? 1 - y / 48 * .42 : edge === "bottom" ? 1 - (47 - y) / 48 * .42 : edge === "left" ? 1 - x / 48 * .42 : edge === "right" ? 1 - (47 - x) / 48 * .42 : edge === "lower" ? 1 - (47 - y) / 48 * .26 : 1;
      const center = edge === "seam" ? 1 - Math.abs(x - 23.5) / 23.5 * .24 : 1;
      const noise = edge === "seam" ? light > 246 ? .30 : chroma > 178 && light > 160 ? .72 : 1 : edge === "lower" ? light > 246 ? .34 : chroma > 168 ? .50 : 1 : light < 28 ? .28 : light > 246 ? .46 : chroma > 156 ? .68 : 1;
      const dark = edge === "seam" ? clamp(1.46 - light / 255 * .54, .90, 1.46) : edge === "lower" ? clamp(1.34 - light / 255 * .42, .92, 1.34) : 1;
      const weight = clamp(distance, .58, 1) * clamp(center, .76, 1) * noise * dark;
      const bucket = (r >> 4) << 8 | (g >> 4) << 4 | b >> 4;
      weights[bucket] += weight; rs[bucket] += r * weight; gs[bucket] += g * weight; bs[bucket] += b * weight;
    }
    let best = -1, weight = 0; for (let i = 0; i < 4096; i++) if (weights[i] > weight) { best = i; weight = weights[i]; }
    return best < 0 ? null : [Math.trunc(rs[best] / weight), Math.trunc(gs[best] / weight), Math.trunc(bs[best] / weight)];
  };
  let total = 0, rsum = 0, gsum = 0, bsum = 0;
  for (let i = 0; i < data.length; i += 4) {
    const alpha = data[i + 3] / 255; if (alpha <= .05) continue;
    const color: RGB = [data[i], data[i + 1], data[i + 2]], [, s, v] = hsv(color);
    if (s < .35 || v < .25 || v > .96) continue;
    const weight = s * (1 - Math.abs(v - .62)) * alpha;
    total += weight; rsum += color[0] * weight; gsum += color[1] * weight; bsum += color[2] * weight;
  }
  return { top: dominant(0, 14, 0, 48, "top"), bottom: dominant(34, 48, 0, 48, "bottom"), right: dominant(0, 48, 34, 48, "right"), left: dominant(0, 48, 0, 14, "left"), lower: dominant(23, 48, 4, 44, "lower"), seam: dominant(28, 45, 5, 43, "seam"), ambient: dominant(0, 48), accent: total < .5 ? null : [Math.trunc(rsum / total), Math.trunc(gsum / total), Math.trunc(bsum / total)], rightUpper: dominant(0, 19, 27, 48, "right"), rightMiddle: dominant(14, 34, 27, 48, "right"), rightLower: dominant(29, 48, 27, 48, "right") };
}
export function coverSource(url?: string | null) {
  if (!url) return "";
  if (url.startsWith("file:///android_asset/artwork/")) return `/pwa/artwork/${url.split("/").pop()}`;
  return url.startsWith("/") || url.startsWith("blob:") ? url : artworkUrl(url);
}
function load(url: string): Promise<Palette> {
  const cached = cache.get(url); if (cached) { cache.delete(url); cache.set(url, cached); return Promise.resolve(cached); }
  const pending = inflight.get(url); if (pending) return pending;
  const promise = new Promise<Palette>(resolve => {
    const image = new Image(); image.crossOrigin = "anonymous";
    const timeout = setTimeout(() => { image.onload = null; image.onerror = null; image.src = ""; resolve(empty); }, 10_000);
    image.onload = () => {
      clearTimeout(timeout);
      try {
        const canvas = document.createElement("canvas"); canvas.width = 48; canvas.height = 48;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        if (!context) { resolve(empty); return; }
        context.drawImage(image, 0, 0, 48, 48); const palette = sample(context.getImageData(0, 0, 48, 48).data);
        cache.set(url, palette); if (cache.size > 128) cache.delete(cache.keys().next().value!); resolve(palette);
      } catch { resolve(empty); }
    };
    image.onerror = () => { clearTimeout(timeout); resolve(empty); }; image.src = coverSource(url);
  }).finally(() => inflight.delete(url));
  inflight.set(url, promise); return promise;
}
export function usePalette(url?: string | null): Palette {
  const [palette, setPalette] = useState<Palette>(() => url ? cache.get(url) || empty : empty);
  useEffect(() => { let active = true; if (!url) { setPalette(empty); return; } void load(url).then(value => { if (active) setPalette(value); }); return () => { active = false; }; }, [url]);
  return palette;
}
