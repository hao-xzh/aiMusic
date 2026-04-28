"use client";

/**
 * Windows borderless 模式（decorum 把原生 frame 拿掉了）下，用户没法用鼠标
 * 在窗口边缘 grab 来调整大小。这里手动在底/左/右三条边以及左下/右下两个角
 * 加 4-8px 的隐形热区，鼠标按下时调 Tauri `startResizeDragging`，效果跟系统
 * 自带的 frame resize 一致。
 *
 * 顶边 / 顶角 留给 decorum 的 32px 标题栏 overlay（拖动 + min/max/close 按钮），
 * 不再做 resize 热区，避免点不到关闭按钮。
 *
 * 仅在 Windows 启用；其它平台返回 null，不影响 macOS 已经原生支持的 frame resize。
 */

import { useEffect, useState } from "react";

const EDGES = ["s", "e", "w", "se", "sw"] as const;
type Edge = (typeof EDGES)[number];

const DIR_MAP: Record<Edge, string> = {
  s: "South",
  e: "East",
  w: "West",
  se: "SouthEast",
  sw: "SouthWest",
};

const CURSORS: Record<Edge, string> = {
  s: "ns-resize",
  e: "ew-resize",
  w: "ew-resize",
  se: "nwse-resize",
  sw: "nesw-resize",
};

export function WindowResizer() {
  const [enabled, setEnabled] = useState(false);

  useEffect(() => {
    if (typeof navigator !== "undefined" && /Windows/i.test(navigator.userAgent)) {
      setEnabled(true);
    }
  }, []);

  if (!enabled) return null;

  const onDown = async (e: React.MouseEvent, edge: Edge) => {
    // 只响应主键，避免右键/中键误触发
    if (e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    try {
      const tauri = (window as unknown as { __TAURI__?: any }).__TAURI__;
      if (!tauri?.window?.getCurrentWindow) return;
      const win = tauri.window.getCurrentWindow();
      await win.startResizeDragging(DIR_MAP[edge]);
    } catch (err) {
      console.warn("[claudio] resize failed", err);
    }
  };

  return (
    <div aria-hidden style={{ position: "fixed", inset: 0, pointerEvents: "none", zIndex: 80 }}>
      {EDGES.map((edge) => (
        <div
          key={edge}
          onMouseDown={(e) => onDown(e, edge)}
          style={{
            ...EDGE_STYLES[edge],
            position: "fixed",
            background: "transparent",
            cursor: CURSORS[edge],
            pointerEvents: "auto",
          }}
        />
      ))}
    </div>
  );
}

const EDGE_STYLES: Record<Edge, React.CSSProperties> = {
  // 底边：避开两端 8px 让位给底角
  s: { left: 8, right: 8, bottom: 0, height: 4 },
  // 左/右边：从 32px（避开 decorum 标题栏 overlay）到底端 8px（让位给底角）
  e: { top: 32, bottom: 8, right: 0, width: 4 },
  w: { top: 32, bottom: 8, left: 0, width: 4 },
  // 底两角
  se: { right: 0, bottom: 0, width: 8, height: 8 },
  sw: { left: 0, bottom: 0, width: 8, height: 8 },
};
