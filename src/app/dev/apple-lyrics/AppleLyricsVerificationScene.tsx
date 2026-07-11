"use client";

import { useEffect, useState, type ComponentType } from "react";

export default function DevOnlyLyricsScene() {
  const [Scene, setScene] = useState<ComponentType | null>(null);

  useEffect(() => {
    let alive = true;
    import("@apple-lyrics-fixture")
      .then((module) => {
        if (alive) setScene(() => module.default);
      })
      .catch((error) => {
        console.error("[apple-lyrics-verify] failed to load fixture", error);
      });
    return () => {
      alive = false;
    };
  }, []);

  return Scene ? <Scene /> : null;
}
