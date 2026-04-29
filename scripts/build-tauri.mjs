import { rmSync } from "node:fs";
import { spawnSync } from "node:child_process";

rmSync(".next", { recursive: true, force: true });
rmSync("out", { recursive: true, force: true });

const result = spawnSync("next", ["build"], {
  stdio: "inherit",
  shell: true,
  env: {
    ...process.env,
    TAURI_BUILD: "1",
  },
});

process.exit(result.status ?? 1);
