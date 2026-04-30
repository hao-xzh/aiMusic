"use client";

/**
 * 播放页 —— 极简。
 *
 * 唯一的 chrome 是 PlayerCard 内部底栏的三个图标（歌词 / 歌单 / 设置）。
 * 顶部不再有 floating 按钮，但顶部空白依旧是 Tauri 窗口拖动区。
 */

import { PlayerCard } from "@/components/PlayerCard";

export default function RadioPage() {
  return (
    <div
      // 整个播放页根容器作为窗口拖动区（Mac/Win 桌面）。
      // PlayerCard 内部的封面 / 控件 / 图标按钮 Tauri 自动豁免，不会触发拖动。
      data-tauri-drag-region
      style={{
        position: "relative",
        flex: "1 1 auto",
        minHeight: 0,
        display: "flex",
        flexDirection: "column",
        // 顶部 padding：避开 Mac 红黄绿 / Win decorum / Android 状态栏。
        // 没有 floating bar 后只需要避开原生标题区（~32px）+ 透气
        paddingTop: "calc(max(env(safe-area-inset-top), 0px) + 44px)",
        paddingBottom: 12,
        paddingLeft: "clamp(8px, 2vw, 16px)",
        paddingRight: "clamp(8px, 2vw, 16px)",
        justifyContent: "safe center",
        overflow: "hidden",
      }}
    >
      <PlayerCard />
    </div>
  );
}
