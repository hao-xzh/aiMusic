import { readdirSync, readFileSync, rmSync, statSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

rmSync(".next", { recursive: true, force: true });
rmSync("out", { recursive: true, force: true });

const nextBin = join("node_modules", ".bin", process.platform === "win32" ? "next.cmd" : "next");

const result = spawnSync(nextBin, ["build"], {
  stdio: "inherit",
  env: {
    ...process.env,
    TAURI_BUILD: "1",
  },
});

if ((result.status ?? 1) !== 0) {
  process.exit(result.status ?? 1);
}

const forbiddenFixtureMarkers = [
  "data-apple-lyrics-fixture",
  "Apple Lyrics Fixture",
  "Pure YRC Fixture",
  "Translated Fixture",
  "Companion Fixture",
  "__setAppleLyricsFixturePosition",
  "data-apple-lyrics-transition-fixture",
  "__openAppleLyricsTransitionFixture",
  "__closeAppleLyricsTransitionFixture",
  "Apple lyrics transition fixture",
  "AppleLyricsVerificationScene",
  "[apple-lyrics-verify]",
];

for (const file of listFiles("out")) {
  if (!/\.(html|txt|js|css|json)$/.test(file)) continue;
  const content = readFileSync(file, "utf8");
  const marker = forbiddenFixtureMarkers.find((candidate) => content.includes(candidate));
  if (marker) {
    console.error(`Production export contains dev Apple lyrics fixture marker "${marker}" in ${file}`);
    process.exit(1);
  }
}

assertDevAppleLyricsRouteIs404();

process.exit(0);

function assertDevAppleLyricsRouteIs404() {
  const exportedFiles = [
    join("out", "dev", "apple-lyrics.html"),
    join("out", "dev", "apple-lyrics.txt"),
  ];

  for (const file of exportedFiles) {
    const content = readFileSync(file, "utf8");
    if (!content.includes("NEXT_HTTP_ERROR_FALLBACK;404")) {
      console.error(`Production export for ${file} is not the expected 404 payload`);
      process.exit(1);
    }
  }

  const html = readFileSync(exportedFiles[0], "utf8");
  if (!html.includes("This page could not be found.")) {
    console.error(`Production export for ${exportedFiles[0]} does not render the Next 404 body`);
    process.exit(1);
  }
}

function listFiles(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const fullPath = join(dir, entry);
    const stat = statSync(fullPath);
    if (stat.isDirectory()) files.push(...listFiles(fullPath));
    else files.push(fullPath);
  }
  return files;
}
