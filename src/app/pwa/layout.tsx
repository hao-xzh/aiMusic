import type { Metadata, Viewport } from "next";

export const metadata: Metadata = {
  title: "PIPO",
  description: "PIPO 音乐播放器，让每一首歌都更合心意。",
  manifest: "/pwa/manifest.webmanifest",
  appleWebApp: { capable: true, statusBarStyle: "black-translucent", title: "Pipo" },
  icons: { apple: "/icon-192.png", icon: "/icon-192.png" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: "cover",
  themeColor: "#070b12",
};

export default function PwaLayout({ children }: { children: React.ReactNode }) {
  return children;
}
