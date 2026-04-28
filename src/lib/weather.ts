"use client";

/**
 * 轻量天气 —— wttr.in 免 key、根据 IP 自动定位。
 *
 * 只取最简版面：summary（"小雨" / "晴" / ...）+ 摄氏温度。
 * 拉不到 / 离线 → 返回 null，AI prompt 那边会 fallback 不提天气。
 *
 * 1 小时内存缓存：天气不会一直变，省调用。
 */

export type Weather = {
  summary: string;
  tempC: number;
};

let memo: { at: number; data: Weather | null } | null = null;
const MEMO_TTL_MS = 60 * 60 * 1000;

export async function getWeather(): Promise<Weather | null> {
  if (memo && Date.now() - memo.at < MEMO_TTL_MS) return memo.data;
  try {
    // wttr.in JSON 格式：?format=j1（按 IP 定位，中文 lang=zh-cn）
    const res = await fetch("https://wttr.in/?format=j1&lang=zh-cn", {
      signal: AbortSignal.timeout(4000),
    });
    if (!res.ok) throw new Error(`wttr ${res.status}`);
    const json = (await res.json()) as {
      current_condition?: Array<{
        temp_C?: string;
        lang_zh_cn?: Array<{ value?: string }>;
        weatherDesc?: Array<{ value?: string }>;
      }>;
    };
    const cur = json.current_condition?.[0];
    if (!cur) throw new Error("no current condition");
    const summary =
      cur.lang_zh_cn?.[0]?.value?.trim() ||
      cur.weatherDesc?.[0]?.value?.trim() ||
      "未知";
    const tempC = Number(cur.temp_C ?? "");
    if (!Number.isFinite(tempC)) throw new Error("bad temp");
    const data = { summary, tempC };
    memo = { at: Date.now(), data };
    return data;
  } catch (e) {
    console.debug("[claudio] 天气拉取失败，跳过", e);
    memo = { at: Date.now(), data: null };
    return null;
  }
}
