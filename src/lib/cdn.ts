/**
 * 网易云 CDN 防盗链绕过。
 *
 * 背景：`p*.music.126.net`（封面图）和 `m*.music.126.net`（音频直链）CDN
 * 都开了 Referer 防盗链。webview 直接加载这些 URL，Referer 是 `tauri://` 或
 * `http://localhost:4321/`，统统 403。试过 `<meta name="referrer">` +
 * `referrerpolicy="no-referrer"`，表现不稳定。
 *
 * 方案：走 Rust 侧注册的 `claudio-cdn` 自定义 scheme 做代理 —— 前端把 CDN
 * URL 改写成 `claudio-cdn://localhost/?u=<encoded>`，Rust 侧 handler 用官方
 * Referer 发出真请求，再把响应 body 塞回来。对 `<img>` 和 `<audio>` 透明。
 *
 * 非网易云域名原样返回（比如 `data:` / 相对路径 / 占位图）。
 *
 * 平台差异：Tauri 2 在 macOS/Linux/iOS 下把自定义 scheme 保留为 `<scheme>://localhost/…`，
 * Windows 和 Android 下都会被 wry 改写成 `http://<scheme>.localhost/…`
 * （Android 经 WebViewAssetLoader 拦截，Windows 是绕 Edge 的 scheme 黑名单）。
 * 默认 useHttpsScheme=false（见 tauri.conf.json），开成 true 才用 `https://`，
 * 这里按 UA 切换一下 base。Rust 侧三种 URI 都能解析，handler 一份就够。
 */
export function cdn(raw: string | null | undefined): string {
  if (!raw) return "";
  if (typeof raw !== "string") return "";
  // 非 http(s) 一律不碰（data:、blob:、相对路径等）
  if (!/^https?:\/\//i.test(raw)) return raw;

  let host = "";
  try {
    host = new URL(raw).hostname.toLowerCase();
  } catch {
    return raw;
  }
  const isNetease =
    host === "music.163.com" ||
    host.endsWith(".music.163.com") ||
    host.endsWith(".music.126.net");
  if (!isNetease) return raw;

  const base = pickProtoBase("claudio-cdn");
  return `${base}?u=${encodeURIComponent(raw)}`;
}

/**
 * 按平台返回自定义 scheme 的 base URL。给 audio scheme 也用上。
 */
export function pickProtoBase(scheme: string): string {
  if (typeof navigator === "undefined") return `${scheme}://localhost/`;
  const ua = navigator.userAgent;
  // Android 和 Windows 都走 wry 的 work-around URI：http://<scheme>.localhost/
  if (/Android/i.test(ua) || /Windows/i.test(ua)) {
    return `http://${scheme}.localhost/`;
  }
  return `${scheme}://localhost/`;
}
