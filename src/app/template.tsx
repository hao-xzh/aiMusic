"use client";

/**
 * 页面过渡动画 —— Next.js App Router 的 template 每次 client navigation 都会
 * 重新挂载，正好用来给"页与页之间的切换"加一帧入场动画。
 *
 * 设计权衡（被用户实测推回来的）：
 * 之前用过 290ms + translateY(6px) + cubic ease，目的是"修跳转之间的黑闪"。
 * 但用户反馈跳转明显变卡 —— 因为：
 *   1. 页面 mount 本身已经要 100-200ms（重 page.tsx 拉数据 + 渲染）
 *   2. 在这段时间外面又叠 290ms 的可见动画，肉眼看到"完整跳转时长"几乎翻倍
 *   3. 桌面 60Hz / 手机 120Hz 都一样，长动画 = 延迟感
 * 现在改成纯 opacity 100ms linear，没有 transform：
 *   - 不再有"位移感"，肉眼只感知"瞬间渐显"，跟 iOS 系统级页面切换的节奏一致
 *   - 100ms 短到不会跟 mount 工作量叠加成感知延迟
 *   - 但仍能盖住 mount 那 1-2 帧的黑闪 —— 那段画面只是底色，opacity 0 时本来
 *     就看不到，等到 mount 一完成动画也基本走完，无缝过渡到新页面
 * 不用 framer-motion / motion：项目本来没装，加一条 CSS 关键帧成本是 0。
 * 不用 View Transitions API：iOS WebView 还没全量支持，先走兼容方案。
 */
export default function Template({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        animation: "pageEnter 100ms linear both",
        flex: "1 1 auto",
        minHeight: 0,
        display: "flex",
        flexDirection: "column",
      }}
    >
      {children}
    </div>
  );
}
