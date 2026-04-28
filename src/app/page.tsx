"use client";

import { PlayerCard } from "@/components/PlayerCard";
import { usePlayer } from "@/lib/player-state";
import Link from "next/link";

export default function RadioPage() {
  const { current } = usePlayer();
  const hasTrack = current !== null;

  return (
    <div
      style={{
        // 横向 padding 跟随窗口收缩 —— 手机宽下不浪费两侧。
        padding: "6px clamp(8px, 2vw, 16px) 8px",
        // safe center —— fit 时居中，溢出时退回 flex-start。
        flex: "1 1 auto",
        minHeight: 0,
        display: "flex",
        flexDirection: "column",
        justifyContent: "safe center",
        overflow: "hidden",
      }}
    >
      {/* 小号页面副标 —— 替代之前的 DotText 点阵，
         保留"页面有标题"的存在感，但用清晰的纯文字，不会被截断也不会糊。 */}
      <div
        style={{
          textAlign: "center",
          fontSize: 11,
          letterSpacing: 4,
          color: "rgba(233,239,255,0.42)",
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
          marginBottom: hasTrack ? 14 : 22,
          textTransform: "uppercase",
        }}
      >
        {hasTrack ? "NOW PLAYING" : "CLAUDIO RADIO"}
      </div>

      <PlayerCard />

      {!hasTrack && (
        <div style={{ marginTop: 18, display: "flex", justifyContent: "center" }}>
          <Link
            href="/distill"
            style={{ ...ctaBtn, textDecoration: "none", display: "inline-block" }}
          >
            ▶ 去挑一首歌开始
          </Link>
        </div>
      )}
    </div>
  );
}

const ctaBtn: React.CSSProperties = {
  padding: "12px 24px",
  borderRadius: 999,
  border: "1px solid rgba(155,227,198,0.45)",
  background: "rgba(155,227,198,0.12)",
  color: "#9be3c6",
  fontWeight: 600,
  letterSpacing: 0.4,
  cursor: "pointer",
  fontSize: 13,
};
