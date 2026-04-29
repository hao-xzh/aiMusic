"use client";

/**
 * App 当下的"环境上下文"—— AI 选歌 / 招呼气泡的输入之一。
 *
 * 要素：
 *   - 时段（早/午/下午/傍晚/晚/深夜）
 *   - 周几 + 是否周末
 *   - 节日（中国农历主要节日 + 几个西方）—— 静态表，不联网
 *   - 天气（先做 stub，由 settings 里以后接 API 时填实）
 *
 * 整个对象是 plain JSON，喂 AI prompt 时直接 stringify 一下。
 */

export type TimeSlot =
  | "early_morning" // 5-8
  | "morning"       // 8-11
  | "noon"          // 11-13
  | "afternoon"     // 13-17
  | "evening"       // 17-20
  | "night"         // 20-23
  | "late_night";   // 23-5

export type AppContext = {
  /** ISO date：2026-04-27 */
  date: string;
  /** "周一" / "周日" */
  dayOfWeek: string;
  isWeekend: boolean;
  timeSlot: TimeSlot;
  /** 该时段的中文人话："深夜" "傍晚刚下班" */
  timeSlotLabel: string;
  /** 节日名（如"清明节" / "圣诞节"），无则 null */
  holiday: string | null;
  /**
   * 距下一个周六还有几天。0=今天就是周末; 1=明天周末; 2-5=工作日还剩几天。
   * 用来让 AI 说出"马上周末了""周一像没拧紧的螺丝"这类节奏提示。
   */
  daysUntilWeekend: number;
  /**
   * 7 天内即将到来的节日（不包含今天）。今天就是节日时这个字段为 null,
   * holiday 字段会带值。让 AI 能说"还有 3 天就国庆"这种期待感。
   */
  upcomingHoliday: { name: string; daysAhead: number } | null;
  /** 天气可选：暂时 stub，未来从设置里接的 API 填进来 */
  weather?: { summary: string; tempC?: number };
};

const ZH_DAYS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

function classifyTime(hour: number): { slot: TimeSlot; label: string } {
  if (hour >= 5 && hour < 8) return { slot: "early_morning", label: "清晨" };
  if (hour >= 8 && hour < 11) return { slot: "morning", label: "上午" };
  if (hour >= 11 && hour < 13) return { slot: "noon", label: "中午" };
  if (hour >= 13 && hour < 17) return { slot: "afternoon", label: "下午" };
  if (hour >= 17 && hour < 20) return { slot: "evening", label: "傍晚" };
  if (hour >= 20 && hour < 23) return { slot: "night", label: "晚上" };
  return { slot: "late_night", label: "深夜" };
}

function pad(n: number) {
  return n.toString().padStart(2, "0");
}

// 阳历节日 —— 用 MM-DD 当 key
const SOLAR_HOLIDAYS: Record<string, string> = {
  "01-01": "元旦",
  "02-14": "情人节",
  "03-08": "妇女节",
  "05-01": "劳动节",
  "05-04": "青年节",
  "06-01": "儿童节",
  "10-01": "国庆节",
  "10-31": "万圣节",
  "12-24": "平安夜",
  "12-25": "圣诞节",
};

// 农历节日没法不联网准确算，先固定一份近年的近似（够主要节日提示用，
// 真要精准等以后接日历 API）。key 是阳历日期。
const LUNAR_APPROX: Record<string, string> = {
  // 2026 年的几个关键农历节日（手填，误差 ±1 天）
  "2026-02-17": "春节",
  "2026-02-31": "元宵节",  // 占位
  "2026-04-04": "清明节",
  "2026-06-19": "端午节",
  "2026-09-25": "中秋节",
};

export function getAppContext(): AppContext {
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth() + 1;
  const d = now.getDate();
  const dow = now.getDay();
  const date = `${y}-${pad(m)}-${pad(d)}`;
  const md = `${pad(m)}-${pad(d)}`;
  const { slot, label } = classifyTime(now.getHours());

  const holiday = LUNAR_APPROX[date] ?? SOLAR_HOLIDAYS[md] ?? null;

  // 距周六: 周六(6)=0, 周日(0)=已周末→0, 周一-周五=6-dow
  const daysUntilWeekend = dow === 0 || dow === 6 ? 0 : 6 - dow;

  return {
    date,
    dayOfWeek: ZH_DAYS[dow],
    isWeekend: dow === 0 || dow === 6,
    timeSlot: slot,
    timeSlotLabel: label,
    holiday,
    daysUntilWeekend,
    upcomingHoliday: findUpcomingHoliday(now, holiday !== null),
  };
}

/**
 * 找未来 7 天内最近的下一个节日。今天本身是节日时跳过(那时 holiday 字段已经带值)。
 * 跨年时(12 月底找 1 月节日)也工作 —— 用 Date 算实际未来日期再查表。
 */
function findUpcomingHoliday(
  base: Date,
  todayIsHoliday: boolean,
): AppContext["upcomingHoliday"] {
  const startOffset = todayIsHoliday ? 1 : 1; // 永远从明天开始往后找
  for (let i = startOffset; i <= 7; i++) {
    const future = new Date(base);
    future.setDate(future.getDate() + i);
    const fy = future.getFullYear();
    const fm = future.getMonth() + 1;
    const fd = future.getDate();
    const fdate = `${fy}-${pad(fm)}-${pad(fd)}`;
    const fmd = `${pad(fm)}-${pad(fd)}`;
    const name = LUNAR_APPROX[fdate] ?? SOLAR_HOLIDAYS[fmd];
    if (name) return { name, daysAhead: i };
  }
  return null;
}

/**
 * 给 AI prompt 用的可读化字符串 —— 比传 JSON 更省 token，也更"自然"。
 */
export function describeContext(ctx: AppContext): string {
  const parts: string[] = [];
  parts.push(`${ctx.date}（${ctx.dayOfWeek}${ctx.isWeekend ? "，周末" : ""}）`);
  parts.push(`${ctx.timeSlotLabel}时段`);
  if (ctx.holiday) {
    parts.push(`今天是${ctx.holiday}`);
  } else if (ctx.upcomingHoliday) {
    parts.push(`再 ${ctx.upcomingHoliday.daysAhead} 天就是${ctx.upcomingHoliday.name}`);
  }
  // 工作日的"马上周末" 信号: 周四=2 天, 周五=1 天
  if (!ctx.isWeekend && ctx.daysUntilWeekend <= 2 && ctx.daysUntilWeekend > 0) {
    if (ctx.daysUntilWeekend === 1) parts.push("明天就周末");
    else if (ctx.daysUntilWeekend === 2) parts.push("再 2 天周末");
  }
  if (ctx.weather) {
    parts.push(
      `天气：${ctx.weather.summary}${ctx.weather.tempC !== undefined ? ` ${ctx.weather.tempC}°C` : ""}`,
    );
  }
  return parts.join(" · ");
}
