import type { Metadata, Viewport } from "next";
import "./globals.css";
import { AppShell } from "@/components/AppShell";

export const metadata: Metadata = {
  title: "Pipo",
  description: "An AI radio distilled from your own playlists.",
  // 网易云 CDN（p*.music.126.net / m*.music.126.net）开了防盗链：
  // 带 Referer: http://localhost:4321/ 直接 403，封面和直链全挂。
  // 设成 no-referrer 就和官方 app 一样不发这个头，CDN 放行。
  referrer: "no-referrer",
};

// viewport-fit=cover：Android WebView 必须显式声明，env(safe-area-inset-top)
// 才会返回真实的状态栏高度（默认是 0）。iOS Safari 也是同样规则。
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
