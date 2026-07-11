"use client";

/**
 * 通用返回按钮 —— 给去 nav 化后的子页面（settings / taste / login）顶部用。
 *
 * 视觉一致：透明 icon button，跟 distill 的 floating top bar 保持同一语言。
 * 行为：传 href 走 Link（用于回首页/上一层固定路由），不传 href 调 router.back()。
 */
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AppIcon } from "./AppIcon";

type Props = {
  href?: string;
  label?: string;
};

export function BackButton({ href, label = "返回" }: Props) {
  const router = useRouter();

  const inner = <AppIcon name="back" size={18} />;

  if (href) {
    return (
      <Link href={href} aria-label={label} title={label} style={chip} className="platform-icon-button">
        {inner}
      </Link>
    );
  }
  return (
    <button onClick={() => router.back()} aria-label={label} title={label} style={chip} className="platform-icon-button">
      {inner}
    </button>
  );
}

// 桌面顶栏统一光学盒；圆角与按压反馈由 platform-icon-button 按 OS 接管。
const chip: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  width: 34,
  height: 34,
  borderRadius: 8,
  border: "1px solid rgba(233,239,255,0.10)",
  background: "transparent",
  color: "rgba(245,247,255,0.86)",
  cursor: "pointer",
  textDecoration: "none",
  WebkitTapHighlightColor: "transparent",
  opacity: 0.92,
  transition: "background 140ms ease, opacity 140ms ease",
};
