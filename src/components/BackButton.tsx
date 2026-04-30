"use client";

/**
 * 通用返回按钮 —— 给去 nav 化后的子页面（settings / taste / login）顶部用。
 *
 * 视觉一致：圆 chip + backdrop blur，跟 distill 的 floating top bar 保持同一语言。
 * 行为：传 href 走 Link（用于回首页/上一层固定路由），不传 href 调 router.back()。
 */
import Link from "next/link";
import { useRouter } from "next/navigation";

type Props = {
  href?: string;
  label?: string;
};

export function BackButton({ href, label = "返回" }: Props) {
  const router = useRouter();

  // 跟 distill 顶部 BackIcon 严格同源（22 svg + 1.9 stroke + 8x14 chevron）—— 否则
  // /settings、/taste、/login 几页的返回图标会比 distill 一眼小一圈，体验不齐
  const inner = (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M16 5l-8 7 8 7" />
    </svg>
  );

  if (href) {
    return (
      <Link href={href} aria-label={label} title={label} style={chip}>
        {inner}
      </Link>
    );
  }
  return (
    <button onClick={() => router.back()} aria-label={label} title={label} style={chip}>
      {inner}
    </button>
  );
}

// 36x36 chip + 20x20 SVG，跟 distill 顶部 chipStyle 同款
const chip: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  width: 36,
  height: 36,
  borderRadius: 999,
  border: "none",
  background: "transparent",
  color: "rgba(245,247,255,0.86)",
  cursor: "pointer",
  textDecoration: "none",
  filter: "drop-shadow(0 1px 6px rgba(0,0,0,0.55))",
  WebkitTapHighlightColor: "transparent",
  transition: "transform 160ms cubic-bezier(0.22,0.61,0.36,1), opacity 160ms ease",
};
