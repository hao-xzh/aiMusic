const path = require("node:path");

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Tauri 会吃静态导出
  output: process.env.TAURI_BUILD === "1" ? "export" : undefined,
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
