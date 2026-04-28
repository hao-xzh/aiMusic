/** @type {import('next').NextConfig} */
const nextConfig = {
  // Tauri 会吃静态导出
  output: process.env.TAURI_BUILD === "1" ? "export" : undefined,
  images: { unoptimized: true },
  experimental: {},
};

module.exports = nextConfig;
