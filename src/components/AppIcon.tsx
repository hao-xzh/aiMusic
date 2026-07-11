"use client";

import type { CSSProperties, ReactNode } from "react";

export type AppIconName =
  | "back"
  | "forward"
  | "search"
  | "play"
  | "pause"
  | "previous"
  | "next"
  | "lyrics"
  | "library"
  | "download"
  | "settings"
  | "profile"
  | "sparkle"
  | "close"
  | "send"
  | "check"
  | "music"
  | "heart"
  | "playing";

type AppIconProps = {
  name: AppIconName;
  size?: number | string;
  className?: string;
  style?: CSSProperties;
};

type IconCss = CSSProperties & { "--app-icon-size": string };

/**
 * 一套语义、两套桌面字形：
 * - macOS：更接近 SF Symbols 的紧凑轮廓、较饱满的 filled media glyph。
 * - Windows：更接近 Fluent 的轻线、正交结构和带轴向的导航箭头。
 *
 * 两套 SVG 同时存在，由 <html data-platform> 纯 CSS 切换，避免 hydration
 * 后才换图标造成的首帧抖动。未知平台默认使用 macOS/通用版本。
 */
export function AppIcon({ name, size = 20, className, style }: AppIconProps) {
  const cssSize = typeof size === "number" ? `${size}px` : size;
  return (
    <span
      className={["app-icon", `app-icon--${name}`, className].filter(Boolean).join(" ")}
      data-app-icon={name}
      aria-hidden="true"
      style={{ ...style, "--app-icon-size": cssSize } as IconCss}
    >
      <svg
        className="app-icon__glyph app-icon__glyph--mac"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.9"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {macGlyph(name)}
      </svg>
      <svg
        className="app-icon__glyph app-icon__glyph--windows"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {windowsGlyph(name)}
      </svg>
    </span>
  );
}

function macGlyph(name: AppIconName): ReactNode {
  switch (name) {
    case "back":
      return <path d="m14.75 5.4-6.6 6.6 6.6 6.6" />;
    case "forward":
      return <path d="m9.25 5.4 6.6 6.6-6.6 6.6" />;
    case "search":
      return <><circle cx="10.6" cy="10.6" r="6.35" /><path d="m15.4 15.4 4.35 4.35" /></>;
    case "play":
      return <path d="M8.1 5.35c0-.88.95-1.43 1.71-.99l9.18 5.42a2.57 2.57 0 0 1 0 4.44l-9.18 5.42c-.76.44-1.71-.11-1.71-.99Z" fill="currentColor" stroke="none" />;
    case "pause":
      return <><rect x="6.2" y="4.7" width="4.25" height="14.6" rx="1.65" fill="currentColor" stroke="none" /><rect x="13.55" y="4.7" width="4.25" height="14.6" rx="1.65" fill="currentColor" stroke="none" /></>;
    case "previous":
      return <><rect x="3.8" y="6.25" width="1.75" height="11.5" rx="0.88" fill="currentColor" stroke="none" /><path d="M13.1 6.85 6.85 11.3a.86.86 0 0 0 0 1.4l6.25 4.45Z" fill="currentColor" stroke="none" /><path d="m20.55 7.15-5.85 4.16a.84.84 0 0 0 0 1.38l5.85 4.16Z" fill="currentColor" stroke="none" /></>;
    case "next":
      return <><path d="m3.45 7.15 5.85 4.16a.84.84 0 0 1 0 1.38l-5.85 4.16Z" fill="currentColor" stroke="none" /><path d="m10.9 6.85 6.25 4.45a.86.86 0 0 1 0 1.4l-6.25 4.45Z" fill="currentColor" stroke="none" /><rect x="18.45" y="6.25" width="1.75" height="11.5" rx="0.88" fill="currentColor" stroke="none" /></>;
    case "lyrics":
      return <><path d="M5.1 5.25h13.8a2.1 2.1 0 0 1 2.1 2.1v8.2a2.1 2.1 0 0 1-2.1 2.1h-7.2l-4.35 2.4v-2.4H5.1A2.1 2.1 0 0 1 3 15.55v-8.2a2.1 2.1 0 0 1 2.1-2.1Z" /><path d="M7.1 10h6.25M7.1 13.45h9.8" /></>;
    case "library":
      return <><path d="M3.5 6.3h10.2M3.5 11.9h8.7M3.5 17.5h6.1" /><path d="M18.7 4.4v9.25" /><path d="M18.7 13.65c-2.75-.35-4.6.7-4.6 2.25 0 1.25 1.08 2.05 2.45 2.05 1.55 0 2.15-.98 2.15-2.58Z" fill="currentColor" stroke="none" /></>;
    case "download":
      return <><path d="M12 3.7v11.1M7.6 10.55 12 14.95l4.4-4.4" /><path d="M4.7 19.8h14.6" /></>;
    case "settings":
      return <><circle cx="12" cy="12" r="2.65" /><path d="M19.1 13.75a7.5 7.5 0 0 0 0-3.5l1.7-1.3-1.85-3.2-2.02.82a7.6 7.6 0 0 0-3.03-1.75L13.6 2.7H9.9l-.3 2.12a7.6 7.6 0 0 0-3.03 1.75l-2.02-.82-1.85 3.2 1.7 1.3a7.5 7.5 0 0 0 0 3.5l-1.7 1.3 1.85 3.2 2.02-.82a7.6 7.6 0 0 0 3.03 1.75l.3 2.12h3.7l.3-2.12a7.6 7.6 0 0 0 3.03-1.75l2.02.82 1.85-3.2Z" /></>;
    case "profile":
      return <><circle cx="12" cy="12" r="8.75" /><circle cx="12" cy="9.25" r="2.65" fill="currentColor" stroke="none" /><path d="M6.85 18.05c.85-2.45 2.7-3.75 5.15-3.75s4.3 1.3 5.15 3.75" /></>;
    case "sparkle":
      return <><path d="M12 2.8c.7 4.45 2.75 6.5 7.2 7.2-4.45.7-6.5 2.75-7.2 7.2-.7-4.45-2.75-6.5-7.2-7.2 4.45-.7 6.5-2.75 7.2-7.2Z" fill="currentColor" stroke="none" /><path d="M18.4 15.2c.3 1.9 1.2 2.8 3.1 3.1-1.9.3-2.8 1.2-3.1 3.1-.3-1.9-1.2-2.8-3.1-3.1 1.9-.3 2.8-1.2 3.1-3.1Z" fill="currentColor" stroke="none" /></>;
    case "close":
      return <path d="m6.2 6.2 11.6 11.6M17.8 6.2 6.2 17.8" />;
    case "send":
      return <><path d="M12 19.2V5.1M6.45 10.7 12 5.1l5.55 5.6" /></>;
    case "check":
      return <path d="m4.6 12.4 4.55 4.55L19.7 6.75" />;
    case "music":
      return <><path d="M10.1 6.05v10.1" /><path d="m10.1 6.05 8.2-1.55v9.9" /><ellipse cx="7.55" cy="17.05" rx="2.55" ry="1.85" fill="currentColor" stroke="none" /><ellipse cx="15.75" cy="15.3" rx="2.55" ry="1.85" fill="currentColor" stroke="none" /></>;
    case "heart":
      return <path d="M12 20.15 4.15 12.8C.1 8.9 2.3 3.65 6.6 3.65c2.3 0 3.75 1.35 5.4 3.35 1.65-2 3.1-3.35 5.4-3.35 4.3 0 6.5 5.25 2.45 9.15Z" fill="currentColor" stroke="none" />;
    case "playing":
      return <><rect className="app-icon__meter app-icon__meter--1" x="5" y="8" width="2.8" height="8" rx="1.4" fill="currentColor" stroke="none" /><rect className="app-icon__meter app-icon__meter--2" x="10.6" y="4.5" width="2.8" height="15" rx="1.4" fill="currentColor" stroke="none" /><rect className="app-icon__meter app-icon__meter--3" x="16.2" y="7" width="2.8" height="10" rx="1.4" fill="currentColor" stroke="none" /></>;
  }
}

function windowsGlyph(name: AppIconName): ReactNode {
  switch (name) {
    case "back":
      return <><path d="M10.5 5.5 4 12l6.5 6.5" /><path d="M4.5 12h15" /></>;
    case "forward":
      return <><path d="m13.5 5.5 6.5 6.5-6.5 6.5" /><path d="M19.5 12h-15" /></>;
    case "search":
      return <><circle cx="10.5" cy="10.5" r="6.25" /><path d="m15 15 4.75 4.75" /></>;
    case "play":
      return <path d="M7.75 5.85c0-1.08 1.16-1.75 2.1-1.2l8.9 5.22a2.47 2.47 0 0 1 0 4.26l-8.9 5.22a1.39 1.39 0 0 1-2.1-1.2Z" fill="currentColor" stroke="none" />;
    case "pause":
      return <><rect x="6.4" y="4.5" width="4" height="15" rx="0.75" fill="currentColor" stroke="none" /><rect x="13.6" y="4.5" width="4" height="15" rx="0.75" fill="currentColor" stroke="none" /></>;
    case "previous":
      return <><path d="M5 5.5v13" /><path d="m17.75 6.1-8.5 5.3a.72.72 0 0 0 0 1.2l8.5 5.3Z" fill="currentColor" stroke="none" /></>;
    case "next":
      return <><path d="M19 5.5v13" /><path d="m6.25 6.1 8.5 5.3a.72.72 0 0 1 0 1.2l-8.5 5.3Z" fill="currentColor" stroke="none" /></>;
    case "lyrics":
      return <><rect x="3.75" y="4.5" width="16.5" height="15" rx="1.5" /><path d="M7 9h10M7 12.5h7.5M7 16h5" /></>;
    case "library":
      return <><path d="M3.75 6.25h10.5M3.75 11.75h8.5M3.75 17.25h6.5" /><path d="M18.25 4.5v10.25" /><ellipse cx="16.25" cy="16" rx="2" ry="1.5" fill="currentColor" stroke="none" /></>;
    case "download":
      return <><path d="M12 3.75v11.5M7.75 11 12 15.25 16.25 11" /><path d="M4.5 19.75h15" /></>;
    case "settings":
      return <><circle cx="12" cy="12" r="3" /><path d="M9.2 4.65 9.85 3h4.3l.65 1.65 1.65.95 1.75-.25 2.15 3.72-1.1 1.4v1.9l1.1 1.4-2.15 3.73-1.75-.25-1.65.95-.65 1.65h-4.3L9.2 20.2l-1.65-.95-1.75.25-2.15-3.73 1.1-1.4v-1.9l-1.1-1.4L5.8 5.35l1.75.25Z" /></>;
    case "profile":
      return <><circle cx="12" cy="8.25" r="3.25" /><path d="M4.75 20c.55-4.1 3.05-6.25 7.25-6.25S18.7 15.9 19.25 20" /></>;
    case "sparkle":
      return <><path d="M11.5 3.25c.45 4.1 2.4 6.05 6.5 6.5-4.1.45-6.05 2.4-6.5 6.5-.45-4.1-2.4-6.05-6.5-6.5 4.1-.45 6.05-2.4 6.5-6.5Z" /><path d="M18.4 15.4c.18 1.7 1 2.52 2.7 2.7-1.7.18-2.52 1-2.7 2.7-.18-1.7-1-2.52-2.7-2.7 1.7-.18 2.52-1 2.7-2.7Z" /></>;
    case "close":
      return <path d="m6.5 6.5 11 11m0-11-11 11" />;
    case "send":
      return <><path d="M4.25 4.75 20 12 4.25 19.25l2.1-6.15L15.5 12l-9.15-1.1Z" /></>;
    case "check":
      return <path d="m4.5 12.25 4.65 4.65L19.5 6.6" />;
    case "music":
      return <><path d="M9.75 6.25v10.4M9.75 6.25 18 4.75v9.9" /><ellipse cx="7.25" cy="17.25" rx="2.5" ry="1.75" /><ellipse cx="15.5" cy="15.25" rx="2.5" ry="1.75" /></>;
    case "heart":
      return <path d="M12 20.25 4.35 13.1C.55 9.45 2.35 4.25 6.75 4.25c2.15 0 3.8 1.2 5.25 3.15 1.45-1.95 3.1-3.15 5.25-3.15 4.4 0 6.2 5.2 2.4 8.85Z" />;
    case "playing":
      return <><rect className="app-icon__meter app-icon__meter--1" x="4.75" y="8.5" width="3" height="7" rx="0.75" fill="currentColor" stroke="none" /><rect className="app-icon__meter app-icon__meter--2" x="10.5" y="5" width="3" height="14" rx="0.75" fill="currentColor" stroke="none" /><rect className="app-icon__meter app-icon__meter--3" x="16.25" y="7" width="3" height="10" rx="0.75" fill="currentColor" stroke="none" /></>;
  }
}
