"use client";

import { useAiAnnouncement } from "@/lib/ai-announcer";

export function AiCoverCaption({ hidden = false }: { hidden?: boolean }) {
  const item = useAiAnnouncement();
  const visible = !!item && !hidden;

  return (
    <div
      key={item?.id ?? "empty"}
      className={`claudio-cover-ai-caption${visible ? " is-visible" : ""}`}
      aria-live="polite"
      aria-hidden={!visible}
    >
      <span className="claudio-cover-ai-caption__logo" aria-hidden>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/icon-192.png" alt="" draggable={false} />
      </span>
      <span className="claudio-cover-ai-caption__text">
        {item?.text ?? ""}
      </span>
    </div>
  );
}
