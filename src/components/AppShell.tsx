"use client";

import { usePathname } from "next/navigation";
import { PlayerProvider } from "@/lib/player-state";
import { AdaptiveDotField } from "@/components/AdaptiveDotField";
import { PlatformTag } from "@/components/PlatformTag";
import { AiPet } from "@/components/AiPet";
import { WindowResizer } from "@/components/WindowResizer";
import { AnalysisAutoResume } from "@/components/AnalysisAutoResume";
import { SmartSessionPlanner } from "@/components/SmartSessionPlanner";

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  if (pathname === "/pwa" || pathname?.startsWith("/pwa/")) {
    return <main className="pipo-app-shell" style={{ flex: "1 1 auto", minHeight: 0, display: "flex", flexDirection: "column" }}>{children}</main>;
  }
  return (
    <PlayerProvider>
      <AdaptiveDotField />
      <PlatformTag />
      <AiPet />
      <WindowResizer />
      <AnalysisAutoResume />
      <SmartSessionPlanner />
      <main style={{ position: "relative", zIndex: 2, flex: "1 1 auto", minHeight: 0, display: "flex", flexDirection: "column", overflowY: "auto" }}>
        {children}
      </main>
    </PlayerProvider>
  );
}
