"use client";

/**
 * Nav v2 —— 跟 Apple Music 桌面/移动端的克制风格对齐：
 *   - 主导航只留两个 tab："电台" / "我的歌单"，纯文字 + 激活态下面一条小竖横线
 *   - 设置降级成右上角齿轮图标，平时不抢戏，需要时一眼就能找到
 *
 * 之前那一排胶囊形状的 tab 是从早期"玻璃感"风格留下的，跟现在的极简冲突。
 *
 * 拖动 / 标题栏：
 *   - macOS：tauri.conf.json 里 titleBarStyle: "Overlay" 把标题栏透明化，
 *     红黄绿三个原生小灯保留在左上角；空白处自带可拖动。
 *   - Windows：decorum 插件把原生标题栏从 NCCALCSIZE 层拿掉，但保留
 *     右上角 min/max/close 三个系统按钮（约 138px 宽）。这条 nav 加
 *     `data-tauri-drag-region` 让用户可以从顶栏拖动整个窗口；同时
 *     在 Windows 下额外给右侧留 152px，避免 settings 齿轮跟系统键打架。
 */

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";

// 标签缩到 2 字 + 小号 line icon 做视觉锚点。
// "我的歌单" → "歌单"：跟"电台"等字数，视觉重量平衡；
// 也和 Apple Music 中文版"广播 / 资料库"那种 2-3 字的精简风一致。
const TABS: Array<{ href: string; label: string; Icon: () => React.ReactElement }> = [
  { href: "/", label: "电台", Icon: RadioIcon },
  { href: "/distill", label: "歌单", Icon: LibraryIcon },
  { href: "/taste", label: "画像", Icon: TasteIcon },
];

export function Nav() {
  const pathname = usePathname();
  // 在 <html> 上挂 data-platform，让 globals.css 按平台调整。
  //   - Windows：留 32px 顶部，避开 decorum 标题栏 overlay
  //   - Android：状态栏/导航栏全部沉浸（背景一路铺到屏幕物理边缘），
  //     不留任何顶部 padding —— 用户明确要求"全沉浸"。
  //   - macOS：traffic-light 由 Tauri overlay 模式处理，不需要前端干预。
  // 检测放在 useEffect 里，避免 SSR/CSR 不一致。
  useEffect(() => {
    if (typeof navigator === "undefined") return;
    const ua = navigator.userAgent;
    if (/Android/i.test(ua)) {
      document.documentElement.dataset.platform = "android";
    } else if (/Windows/i.test(ua)) {
      document.documentElement.dataset.platform = "windows";
    }
  }, []);

  return (
    // data-tauri-drag-region：让整条 nav 成为可拖动区域。
    // 内部的 <Link>/<button> 是交互元素，会被 tauri 自动豁免，不会触发拖动。
    // Windows 下系统三个键由 decorum 在头顶 32px 处单独画，不占 nav 横向空间，
    // 所以 nav 自身不再需要右侧留白。
    <nav data-tauri-drag-region style={navStyle}>
      {/* 左侧 spacer 让 tabs 永远绝对居中，不被右侧齿轮拉偏 */}
      <div style={sideSlot} data-tauri-drag-region />

      <div style={tabsRow} data-tauri-drag-region>
        {TABS.map(({ href, label, Icon }) => {
          const active = pathname === href;
          return (
            <Link key={href} href={href} style={tabLink(active)}>
              <Icon />
              <span>{label}</span>
            </Link>
          );
        })}
      </div>

      <div
        style={{ ...sideSlot, justifyContent: "flex-end" }}
        data-tauri-drag-region
      >
        <Link
          href="/settings"
          aria-label="设置"
          title="设置"
          style={iconBtn(pathname === "/settings")}
        >
          <GearIcon />
        </Link>
      </div>
    </nav>
  );
}

function RadioIcon() {
  // 中心实心点 + 两层弧线，像信号扩散。比拟物的天线塔更克制
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      aria-hidden
    >
      <circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none" />
      <path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4" />
      <path d="M4.5 4.5a10 10 0 0 0 0 15M19.5 4.5a10 10 0 0 1 0 15" />
    </svg>
  );
}

function TasteIcon() {
  // 一颗心形（音乐口味的隐喻），右下一颗小亮点表示"已蒸馏出来的画像"
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M12 19c-4-2.5-7-5.4-7-9.2A3.8 3.8 0 0 1 12 7a3.8 3.8 0 0 1 7 2.8c0 3.8-3 6.7-7 9.2z" />
      <circle cx="18" cy="6" r="1.3" fill="currentColor" stroke="none" />
    </svg>
  );
}

function LibraryIcon() {
  // 三条横线 = 列表；右下一个音符头 = 这个列表是音乐
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M3.5 6h11M3.5 12h11M3.5 18h7" />
      <path d="M19.5 4v9" />
      <ellipse cx="17.7" cy="14" rx="2.2" ry="1.6" fill="currentColor" stroke="none" />
    </svg>
  );
}

function GearIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}

const navStyle: React.CSSProperties = {
  position: "relative",
  zIndex: 2,
  display: "flex",
  alignItems: "center",
  padding: "clamp(10px, 1.8vh, 18px) clamp(12px, 3vw, 24px)",
  gap: 8,
};

const sideSlot: React.CSSProperties = {
  flex: 1,
  display: "flex",
  alignItems: "center",
  minWidth: 0,
};

const tabsRow: React.CSSProperties = {
  display: "flex",
  gap: "clamp(20px, 6vw, 36px)",
  alignItems: "center",
  flex: "0 0 auto",
};

const tabLink = (active: boolean): React.CSSProperties => ({
  position: "relative",
  display: "inline-flex",
  alignItems: "center",
  gap: 6,
  padding: "6px 2px",
  color: active ? "#f5f7ff" : "rgba(233,239,255,0.5)",
  fontSize: "clamp(13px, 3.6vw, 15px)",
  fontWeight: active ? 600 : 500,
  textDecoration: "none",
  letterSpacing: 0.3,
  // 激活态用一条小横线作指示，比胶囊背景克制 —— Apple Music sidebar 是同一思路
  borderBottom: active
    ? "1.5px solid #f5f7ff"
    : "1.5px solid transparent",
  transition: "color 160ms ease, border-color 160ms ease",
});

const iconBtn = (active: boolean): React.CSSProperties => ({
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  width: 34,
  height: 34,
  borderRadius: 999,
  color: active ? "#f5f7ff" : "rgba(233,239,255,0.5)",
  background: "transparent",
  textDecoration: "none",
  transition: "color 160ms ease, background 160ms ease",
});
