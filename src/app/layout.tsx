import type { Metadata } from "next";
import "./globals.css";
import { PlayerProvider } from "@/lib/player-state";
import { AdaptiveDotField } from "@/components/AdaptiveDotField";
import { Nav } from "@/components/Nav";
import { AiPet } from "@/components/AiPet";
import { WindowResizer } from "@/components/WindowResizer";
import { AnalysisAutoResume } from "@/components/AnalysisAutoResume";

export const metadata: Metadata = {
  title: "Claudio",
  description: "An AI radio distilled from your own playlists.",
  // 网易云 CDN（p*.music.126.net / m*.music.126.net）开了防盗链：
  // 带 Referer: http://localhost:4321/ 直接 403，封面和直链全挂。
  // 设成 no-referrer 就和官方 app 一样不发这个头，CDN 放行。
  referrer: "no-referrer",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <PlayerProvider>
          <AdaptiveDotField />
          <Nav />
          <AiPet />
          <WindowResizer />
          <AnalysisAutoResume />
          {/*
            main 吃掉 body flex 列里 Nav 之外的全部空间。
            flex:1 + minHeight:0 是经典的 "占满剩余又能被内容撑大" 组合 ——
            页面短时铺满一屏（子元素可以 justify-center），页面长时才出滚动条。
          */}
          <main
            style={{
              position: "relative",
              zIndex: 2,
              flex: "1 1 auto",
              minHeight: 0,
              display: "flex",
              flexDirection: "column",
              paddingBottom: 12,
              // 非播放页（歌单/设置）内容可能很长 —— 给 main 装一个内层滚动，
              // 不污染到 body（body 已经 overflow: hidden）。播放页自己再
              // 用 overflow: hidden 把这个滚动锁死。
              overflowY: "auto",
            }}
          >
            {children}
          </main>
        </PlayerProvider>
      </body>
    </html>
  );
}
