const path = require("node:path");
const pwaStandalone = process.env.TAURI_BUILD !== "1" && process.env.PIPO_PWA_STANDALONE === "1";

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Tauri 会吃静态导出
  output: process.env.TAURI_BUILD === "1" ? "export"
    : pwaStandalone ? "standalone" : undefined,
  distDir: pwaStandalone ? ".next-router" : ".next",
  // Runtime sessions and host binaries are never deployment inputs.
  outputFileTracingExcludes: {
    "/*": ["./.pwa-data/**/*", "./services/pwa-bridge/target/**/*"],
  },
  // PWA server routes are excluded from Tauri's static export.
  pageExtensions: process.env.TAURI_BUILD === "1"
    ? ["tsx", "ts", "jsx", "js"]
    : ["pwa.ts", "tsx", "ts", "jsx", "js"],
  images: { unoptimized: true },
  experimental: {},
  webpack(config, { dev }) {
    config.resolve.alias["@apple-lyrics-fixture"] = path.resolve(
      __dirname,
      dev
        ? "src/app/dev/apple-lyrics/AppleLyricsVerificationScene.fixture.tsx"
        : "src/app/dev/apple-lyrics/AppleLyricsVerificationScene.stub.tsx",
    );
    return config;
  },
};

module.exports = nextConfig;
