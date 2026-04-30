"use client";

/**
 * AI 宠物 —— 右下角浮窗 + 点击打开聊天浮层。
 *
 * 形态：
 *   - 闲置：48×48 圆形浮窗，呼吸光晕。每日首开会自己冒一句招呼气泡（5s 自动收）。
 *   - 点击：~360×500 的 chat 浮层。再次点击 pet 关闭。
 *
 * 数据流：
 *   - 用户发送 → pet-agent.chat(history, userText) → assistant 回复
 *   - 如果 assistant 携带 play 指令 → 用 player.playNetease(first, allResolved) 切歌
 *   - history 只在 session 内（每次 app 启动重置）—— 控住 prompt 体积
 */

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
} from "react";
import { usePlayer } from "@/lib/player-state";
import { cdn } from "@/lib/cdn";
import "./AiPet.css";
import {
  chat as petChat,
  generateDailyGreeting,
  shouldGreetToday,
  markGreeted,
  commentOnTrack,
  type ChatMessage,
} from "@/lib/pet-agent";
import { getTrackSemanticProfile } from "@/lib/track-semantic-profile";

// 空状态提示词池 —— 整体调性: 短、抽象、像真朋友一句话开场。
// 每次开 panel 随机一句，避免每次看到一样的台词。**绝不**给"示例式提示"
// （"工作听 / 走路听 / 陪我熬夜" 那种 —— 既冗长又像 AI 教用户怎么说话）。
const EMPTY_HINTS = [
  "在。说吧。",
  "醒着呢。",
  "嗯？",
  "想听啥。",
  "随便说。",
  "说点。",
  "嗯。",
];

// idle 状态下的副标题（没在播歌时）—— 同样调性
const IDLE_SUBTITLES = [
  "在",
  "醒着",
  "嗯",
];

export function AiPet() {
  const player = usePlayer();
  const [open, setOpen] = useState(false);
  // closing：进出动画用 —— 点关闭后保留挂载 220ms 跑 exit 动画
  const [closing, setClosing] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [pending, setPending] = useState(false);
  const [hint, setHint] = useState<string | null>(null);
  const greetStartedRef = useRef(false);
  // 每次组件挂载时挑一句 —— 整个 session 内稳定，避免一打开就重抽
  const emptyHint = useRef(
    EMPTY_HINTS[Math.floor(Math.random() * EMPTY_HINTS.length)],
  ).current;
  const idleSubtitle = useRef(
    IDLE_SUBTITLES[Math.floor(Math.random() * IDLE_SUBTITLES.length)],
  ).current;
  // 锚点位置（视口坐标）+ 模式
  //   - "attached": 找到封面时，锚点 = 封面右下角内侧；绳子从挂点垂下
  //   - "free":     未找到封面 / 歌词页打开时，锚点 = 屏幕右下角附近；无绳
  // 跟旧版 coverHook 的不同：永远是值（不会 null），让 inline left/top 永远生效，
  // 配合 CSS transition 就能在 attached ↔ free 之间跑平滑移动动画。
  const [anchorPos, setAnchorPos] = useState<{
    x: number;
    y: number;
    mode: "attached" | "free";
  } | null>(null);
  // 兼容旧字段：组件内仍有几处依赖 coverHook 形状（气泡定位 / class 切换）。
  // 由 anchorPos 派生：只有 attached 模式才向下游传 hook 信息。
  const coverHook = anchorPos?.mode === "attached"
    ? { x: anchorPos.x, y: anchorPos.y }
    : null;
  // 跟封面取色出来的"水珠主色"（rgb 三元组），默认薄荷绿
  const [orbRgb, setOrbRgb] = useState<[number, number, number]>([
    155, 227, 198,
  ]);
  // 提亮版（绳子用）—— 往白色拉 55%，保证暗色封面下也看得见
  const orbRgbLight = lightenTowardWhite(orbRgb, 0.55);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const petBtnRef = useRef<HTMLButtonElement | null>(null);
  const closeTimerRef = useRef<number | null>(null);
  // 单曲点评：避免对同一首歌重复评论 / 用户狂切歌时浪费 AI 调用
  const lastCommentTrackIdRef = useRef<number | null>(null);
  const lastTrackForCommentRef = useRef<{ title: string; artist: string } | null>(null);
  const commentDebounceRef = useRef<number | null>(null);
  const hintHideRef = useRef<number | null>(null);
  // 频次自适应: 用户狂切歌时(30s 内 3+ 次切换),进入冷却期不再触发 AI 点评
  const recentTrackChangesRef = useRef<number[]>([]);
  const skipCooldownUntilRef = useRef<number>(0);

  // 显示一次 hint 气泡，duration 后自动收。多次调用会清掉上一条 timer。
  const showHint = useCallback((text: string, durationMs: number) => {
    if (hintHideRef.current) {
      clearTimeout(hintHideRef.current);
      hintHideRef.current = null;
    }
    setHint(text);
    hintHideRef.current = window.setTimeout(() => {
      setHint(null);
      hintHideRef.current = null;
    }, durationMs);
  }, []);

  // 退出动画：先 closing=true 跑 220ms 动画，再真正 unmount
  const requestClose = useCallback(() => {
    if (closeTimerRef.current != null) return;
    setClosing(true);
    closeTimerRef.current = window.setTimeout(() => {
      closeTimerRef.current = null;
      setOpen(false);
      setClosing(false);
    }, 220);
  }, []);

  // 点 panel 和 pet 之外的任何地方都关
  useEffect(() => {
    if (!open || closing) return;
    const onDocDown = (e: MouseEvent) => {
      const target = e.target as Node | null;
      if (!target) return;
      if (panelRef.current?.contains(target)) return;
      if (petBtnRef.current?.contains(target)) return;
      requestClose();
    };
    document.addEventListener("mousedown", onDocDown);
    return () => document.removeEventListener("mousedown", onDocDown);
  }, [open, closing, requestClose]);
  // 风场 + 节拍（v3）—— 让"在跟音乐摆"肉眼可见
  //
  // v2 一版的问题：摆动只走 rotate，且持续风力有 60% 的固定底
  // （`0.6 + ampSmooth * 2.6`），开关音乐对比度不够；节拍只反映在角速度上，
  // 也很难一眼看出是"节奏"。
  //
  // v3 把视觉反馈拆成三通道，互相不打架：
  //   1. 风力摆（rotate） —— 仅音乐驱动，amp=0 时风力就是 0，没有底噪。
  //   2. 节拍弹跳（translateY + scale） —— 检测到尖峰就给 `--beat` 拍一下
  //      （0..1），CSS 用它做向上跳一帧 + 球体微胀，肉眼立刻能"看见鼓点"。
  //   3. amp 颜色 —— 维持原来的 brightness/saturate 微调。
  //
  // amp 归一化也调了：之前 `(amp - 0.15) / 0.8` 把"静音"映成 0.125，
  // 静音/有音乐差别本就不到 8 倍。现在 `(amp - 0.25) / 0.7` 让真静音=0，
  // 整个静→响梯度铺满 [0,1]。
  useEffect(() => {
    let raf = 0;
    const reduceMotion = window.matchMedia(
      "(prefers-reduced-motion: reduce)",
    ).matches;

    // 三个互不共振的相位 + 频率，让"底风"看起来不是周期性的
    const phaseA = randomRange(0, Math.PI * 2);
    const phaseB = randomRange(0, Math.PI * 2);
    const phaseC = randomRange(0, Math.PI * 2);

    let angle = 0; // deg
    let velocity = 0; // deg/s
    let ampSmooth = 0;
    // 慢速跟踪 amp 的"基准线"，用来检测瞬时尖峰 = 节拍
    let ampSlow = 0;
    let lastBeatAt = 0;
    // 节拍包络 0..1：被命中后立即拉到 ~1，按指数衰减回 0
    let beatLevel = 0;
    let lastT = performance.now();
    let nextGustAt = lastT + randomRange(1500, 3500);

    // 弹簧 / 阻尼参数：c² < 4k 时是欠阻尼（会自然摆几下再停），更像真物体
    // K 偏小 + C 偏小 → 摆动周期长 + 余韵足，看着像被真风托住
    const SPRING_K = 20; // 越大回弹越快
    const SPRING_C = 3.6; // 越大越快静止；和 K 配比决定摆动余韵

    const tick = () => {
      const btn = petBtnRef.current;
      const anchor = btn?.parentElement;
      if (!anchor) {
        raf = requestAnimationFrame(tick);
        return;
      }

      const now = performance.now();
      // dt clamp 到 [1ms, 50ms]，背景 tab 切回来不会一帧跳很远
      const dt = Math.min(0.05, Math.max(0.001, (now - lastT) / 1000));
      lastT = now;

      // 暂停 / 没歌时，player-state 把 __claudioAmp 写成 0 —— 直接归零
      // 安静时 amp ≈ 0.25 ；音乐时 amp 在 0.25~0.95 间波动
      const rawAmp =
        (window as unknown as { __claudioAmp?: number }).__claudioAmp ?? 0;
      // [0.25, 0.95] → [0, 1] —— 真静音=0，高潮=1，整段铺满
      const norm = Math.max(0, Math.min(1, (rawAmp - 0.25) / 0.7));
      // EMA 平滑：1 - exp(-dt/τ)；τ ≈ 0.2s 给出 ~150ms 半衰期
      const ampLerp = 1 - Math.exp(-dt / 0.2);
      ampSmooth += (norm - ampSmooth) * ampLerp;
      // 慢通道（半衰期 ~600ms）追"基准能量"，norm 高于它就是瞬时尖峰=节拍
      const slowLerp = 1 - Math.exp(-dt / 0.8);
      ampSlow += (norm - ampSlow) * slowLerp;

      const isAttached = anchor.classList.contains("is-attached");
      const motionScale = reduceMotion ? 0.2 : isAttached ? 1 : 0.85;

      // 持续风力：三层低频，频率挑了非整数倍 (0.18 / 0.31 / 0.49 Hz)，
      // 看着像"风一阵阵地吹"，没有可感知的循环
      // **关键变化**：直接乘 ampSmooth，不再有 0.6 的固定底 —— 没音乐 = 不摆
      const t = now / 1000;
      const breeze =
        (Math.sin(t * 0.18 + phaseA) * 1.0 +
          Math.sin(t * 0.31 + phaseB) * 0.55 +
          Math.sin(t * 0.49 + phaseC) * 0.3) *
        ampSmooth *
        3.4;

      // **节拍冲量**：检测 amp 瞬时尖峰（norm 比慢通道 ampSlow 高出阈值）
      // 命中后两件事一起发：(a) velocity 一拍让钟摆甩出去；(b) beatLevel 拉满，
      // CSS 用它做"咚"一下的微胀 + filter 闪。
      //
      // 关键改动：beatDir 不再 random —— 跟当前角速度方向走。原理跟"推秋千"一致：
      //   如果钟摆正在向左荡(velocity<0)，那这拍也往左推，能量叠加，下一次更高
      //   连续命中重音 → 摆动越来越大，肉眼很容易跟上鼓点
      // 角速度太小时（接近静止）才用随机方向破对称
      const beatExcess = norm - ampSlow;
      if (beatExcess > 0.12 && now - lastBeatAt > 160 && ampSmooth > 0.05) {
        const beatImpulse = (5 + beatExcess * 26) * motionScale;
        const beatDir =
          Math.abs(velocity) > 1.5
            ? Math.sign(velocity)
            : Math.random() > 0.5
              ? 1
              : -1;
        velocity += beatImpulse * beatDir;
        // beatLevel 累计但封顶 1，连续重音不会越加越大
        beatLevel = Math.min(1, beatLevel + 0.6 + beatExcess * 1.4);
        lastBeatAt = now;
      }
      // 节拍包络指数衰减 —— 半衰期 ~110ms（短促有力，不拖泥带水）
      const beatDecay = 1 - Math.exp(-dt / 0.16);
      beatLevel += (0 - beatLevel) * beatDecay;

      // 阵风冲量：节拍之外的"自然摆"。只在确实有音乐时才发，安静时不要乱蹦。
      if (now >= nextGustAt) {
        if (ampSmooth > 0.08) {
          const musicLift = 0.8 + ampSmooth * 1.9;
          const impulseMag =
            randomRange(5.0, 11.0) * musicLift * motionScale;
          const impulseDir = Math.random() > 0.5 ? 1 : -1;
          velocity += impulseMag * impulseDir;
        }
        // 音乐越激情，下一拍来得越快
        nextGustAt =
          now + randomRange(1500, 4800) - ampSmooth * 1800;
      }

      // 弹簧-阻尼积分：F = k(target - x) - c·v
      const target = breeze * motionScale * 2.8;
      const accel = (target - angle) * SPRING_K - velocity * SPRING_C;
      velocity += accel * dt;
      angle += velocity * dt;

      // 物理层硬上限 ±18°，让节拍 punch 看得出来
      if (angle > 18) {
        angle = 18;
        if (velocity > 0) velocity = 0;
      } else if (angle < -18) {
        angle = -18;
        if (velocity < 0) velocity = 0;
      }

      anchor.style.setProperty("--amp", ampSmooth.toFixed(3));
      anchor.style.setProperty("--wind-rotate", `${angle.toFixed(2)}deg`);
      anchor.style.setProperty("--beat", beatLevel.toFixed(3));

      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  // 跟踪封面主色 —— 让水珠和绳子自动跟封面调色，不再突兀
  const coverUrl = player.current?.cover;
  useEffect(() => {
    if (!coverUrl) {
      setOrbRgb([155, 227, 198]);
      // 没封面 = 默认深底色，Windows 三键用浅图标
      writeTitlebarFg(false);
      return;
    }
    let cancelled = false;
    sampleVividColor(cdn(coverUrl))
      .then((rgb) => {
        if (!cancelled) setOrbRgb(rgb);
      })
      .catch(() => {
        if (!cancelled) setOrbRgb([155, 227, 198]);
      });
    // 顺手也把"封面主体亮度"算出来，给 Windows 三键调色用
    sampleAvgLuminance(cdn(coverUrl))
      .then((lum) => {
        if (cancelled) return;
        // 注意：底层有暗化叠层（CSS overlayStyle ≈ 0.55 black），所以
        // 看到的"实际背景"比封面均值暗约 40%。简单 thresholding：
        // 封面均值 > 0.65 → 大致还是亮底，三键用深色；否则用浅色
        writeTitlebarFg(lum > 0.65);
      })
      .catch(() => {
        if (!cancelled) writeTitlebarFg(false);
      });
    return () => {
      cancelled = true;
    };
  }, [coverUrl]);

  // 跟踪封面右下角位置，让光点真的"挂在封面上"
  useEffect(() => {
    let lastX = -1;
    let lastY = -1;
    let lastMode: "attached" | "free" | "" = "";
    // free 模式（默认右下角）的锚点视口坐标。
    // 几何对应：anchor 自身 (0,0) → 内部 .claudio-pet 在 (-22, +14) → 球
    // 左上角 (anchor.x - 22, anchor.y + 14)，球 44×44 → 球右下角
    // (anchor.x + 22, anchor.y + 58)。要让球右下角对齐 (vw - 36, vh - 56) →
    //   anchor.x = vw - 58, anchor.y = vh - 114.
    const freePos = () => ({
      x: window.innerWidth - 58,
      y: window.innerHeight - 114,
    });
    const apply = (x: number, y: number, mode: "attached" | "free") => {
      // 对每帧节流：差距太小不重设，避免 React re-render 风暴
      if (
        mode === lastMode &&
        Math.abs(x - lastX) < 0.5 &&
        Math.abs(y - lastY) < 0.5
      ) {
        return;
      }
      lastX = x;
      lastY = y;
      lastMode = mode;
      setAnchorPos({ x, y, mode });
    };
    const measure = () => {
      // 歌词页打开时，宠物退到默认右下角，CSS transition 会自动跑移动动画
      const immersiveOpen =
        document.body.dataset.claudioImmersive === "1";
      if (immersiveOpen) {
        const p = freePos();
        apply(p.x, p.y, "free");
        return;
      }
      const cover = document.querySelector<HTMLElement>("[data-claudio-cover]");
      if (!cover) {
        const p = freePos();
        apply(p.x, p.y, "free");
        return;
      }
      const r = cover.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) return;
      // 挂在封面右下角内侧 14px、底边线上 —— 让绳子从封面下边框右侧出来
      apply(r.right - 14, r.bottom, "attached");
    };
    measure();
    const ro = new ResizeObserver(measure);
    const mo = new MutationObserver(measure);
    let observed: HTMLElement | null = null;
    const reobserve = () => {
      const cover = document.querySelector<HTMLElement>("[data-claudio-cover]");
      if (cover && cover !== observed) {
        if (observed) ro.unobserve(observed);
        ro.observe(cover);
        observed = cover;
      } else if (!cover && observed) {
        ro.unobserve(observed);
        observed = null;
      }
    };
    reobserve();
    mo.observe(document.body, { childList: true, subtree: true });
    // 监听 body 的 data-claudio-immersive 属性切换 —— PlayerCard 进入 / 退出
    // 沉浸式歌词时会改这个 flag。靠这个观察器直接触发 measure，避免依赖 250ms
    // 兜底定时器带来的 lag（不然宠物会比歌词页晚 ~250ms 才开始移动）
    mo.observe(document.body, {
      attributes: true,
      attributeFilter: ["data-claudio-immersive"],
    });
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    // 封面有 4s 的 scale 1↔1.012 缓动，靠定时拍打跟住
    const id = window.setInterval(() => {
      reobserve();
      measure();
    }, 250);
    return () => {
      ro.disconnect();
      mo.disconnect();
      window.removeEventListener("resize", measure);
      window.removeEventListener("scroll", measure, true);
      clearInterval(id);
    };
  }, []);

  // 每日首开主动招呼（不打开 panel，只在 pet 上方冒一个气泡）
  useEffect(() => {
    if (greetStartedRef.current) return;
    greetStartedRef.current = true;
    if (!shouldGreetToday()) return;

    (async () => {
      try {
        const message = await generateDailyGreeting();
        markGreeted();
        showHint(message, 5500);
      } catch (e) {
        console.debug("[claudio] pet daily greeting 失败", e);
      }
    })();
    // showHint 是稳定 callback —— 这里就是 once-on-mount，不订阅依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ----- 单曲点评气泡 -----
  // 每首新歌开始播 → 1.5s 后让 Claudio 说一句"为什么放这首给 TA"。
  // 多重护栏避免浪费 AI 调用:
  //   1. 同一首 id 不会重复评论
  //   2. panel 打开时跳过(气泡看不见)
  //   3. 窗口失焦/被遮 (document.hidden) 跳过
  //   4. 用户狂切歌(30s 内 3+ 次)进入 60s 冷却期
  //   5. 1.5s debounce —— 用户连刷时只评最后停留的那首
  useEffect(() => {
    const cur = player.current;
    if (!cur?.neteaseId) return;
    if (lastCommentTrackIdRef.current === cur.neteaseId) return;
    if (open) return;

    // 频次护栏: 记录这次切歌时间, 30s 内累计 3+ 次就进入冷却
    const now = Date.now();
    const recent = recentTrackChangesRef.current;
    recent.push(now);
    while (recent.length > 0 && now - recent[0] > 30_000) recent.shift();
    if (recent.length >= 3) {
      // 用户在 shopping 模式,suspend 60s
      skipCooldownUntilRef.current = now + 60_000;
      console.debug("[claudio] 切歌过密, 冷却 60s 不触发点评");
    }
    if (now < skipCooldownUntilRef.current) {
      lastCommentTrackIdRef.current = cur.neteaseId; // 标记已"处理"防重入
      return;
    }

    // 窗口不可见(浏览器 tab 背后 / app 失焦)时跳过 —— 看不见气泡浪费 AI
    if (typeof document !== "undefined" && document.hidden) {
      lastCommentTrackIdRef.current = cur.neteaseId;
      return;
    }

    if (commentDebounceRef.current) {
      clearTimeout(commentDebounceRef.current);
      commentDebounceRef.current = null;
    }

    const trackId = cur.neteaseId;
    const trackTitle = cur.title;
    const trackArtist = cur.artist;

    commentDebounceRef.current = window.setTimeout(async () => {
      lastCommentTrackIdRef.current = trackId;
      try {
        const userContext = [...messages]
          .reverse()
          .find((m) => m.role === "user")?.text;
        let semantic = null;
        try {
          semantic = await getTrackSemanticProfile(trackId);
        } catch {
          /* 没有 profile 就走 track 名 + artist 兜底 */
        }
        const previous = lastTrackForCommentRef.current ?? undefined;
        const comment = await commentOnTrack({
          track: {
            id: trackId,
            title: trackTitle,
            artist: trackArtist,
            moods: semantic?.vibe.moods,
            scenes: semantic?.vibe.scenes,
            genres: semantic?.style.genres,
            summary: semantic?.summary,
          },
          userContext,
          previousTrack: previous,
        });
        lastTrackForCommentRef.current = { title: trackTitle, artist: trackArtist };
        if (comment && !open) {
          showHint(comment, 6000);
        }
      } catch (e) {
        console.debug("[claudio] track comment 失败", e);
      }
    }, 1500);

    return () => {
      if (commentDebounceRef.current) {
        clearTimeout(commentDebounceRef.current);
        commentDebounceRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player.current?.neteaseId, open]);

  // 滚到底
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [messages, pending]);

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || pending) return;
      setInput("");
      const next: ChatMessage[] = [
        ...messages,
        { role: "user", text: trimmed },
      ];
      setMessages(next);
      setPending(true);
      try {
        const res = await petChat({
          history: messages, // 不含刚发出的这条；和 system prompt 一起组语境
          userText: trimmed,
          currentTrack: player.current?.neteaseId
            ? {
                neteaseId: player.current.neteaseId,
                title: player.current.title,
                artist: player.current.artist,
              }
            : null,
        });
        const assistantMsg: ChatMessage = {
          role: "assistant",
          // res.text 永远有人格 reply（pet-agent 已经保证）。极端兜底 "嗯。"
          text: res.text || "嗯。",
          play: res.play ?? undefined,
        };
        setMessages([...next, assistantMsg]);

        // 触发播放
        if (res.play && res.resolvedTracks.length > 0) {
          const [head, ...rest] = res.resolvedTracks;
          // pet-agent 内部已经跑过 smoothQueue，不要再来一遍
          await player.playNetease(head, [head, ...rest], { smooth: false });
        }
      } catch (e) {
        console.warn("[claudio] pet send failed", e);
        setMessages([
          ...next,
          { role: "assistant", text: "我这边卡了一下，再说一次？" },
        ]);
      } finally {
        setPending(false);
      }
    },
    [messages, pending, player],
  );

  return (
    <>
      {/* 招呼气泡 —— 玻璃 + 封面色辉。位置跟宠物当前位置走（attached 时贴封面挂点,
          否则回退到默认右下角）。气泡的"尾巴"（小圆角那一角）指向宠物。 */}
      {hint && !open && (
        <div
          style={
            {
              ...hintBubble,
              ...computeHintBubblePosition(coverHook),
              "--orb-rgb": `${orbRgb[0]}, ${orbRgb[1]}, ${orbRgb[2]}`,
              "--orb-rgb-light": `${orbRgbLight[0]}, ${orbRgbLight[1]}, ${orbRgbLight[2]}`,
            } as CSSProperties
          }
        >
          {hint}
        </div>
      )}

      {/* chat 浮层 —— closing 时挂 exit 类跑动画再卸载 */}
      {open && (
        <div
          ref={panelRef}
          className={`claudio-pet-panel${closing ? " is-closing" : ""}`}
          role="dialog"
          aria-label="Claudio 聊天"
          style={
            {
              "--orb-rgb": `${orbRgb[0]}, ${orbRgb[1]}, ${orbRgb[2]}`,
              "--orb-rgb-light": `${orbRgbLight[0]}, ${orbRgbLight[1]}, ${orbRgbLight[2]}`,
            } as CSSProperties
          }
        >
          <div style={panelHeader}>
            <span style={panelHeaderCore} aria-hidden />
            <div style={panelHeaderText}>
              <div style={panelHeaderTitle}>Claudio</div>
              <div style={panelHeaderSubtitle}>
                {pending
                  ? "想想看…"
                  : player.isPlaying && player.current
                    ? `在听 · ${player.current.title}`
                    : idleSubtitle}
              </div>
            </div>
            <button
              onClick={requestClose}
              aria-label="关闭"
              style={panelCloseBtn}
            >
              ×
            </button>
          </div>

          <div ref={scrollRef} style={panelScroll}>
            {messages.length === 0 && (
              <div style={emptyHintStyle}>{emptyHint}</div>
            )}
            {messages.map((m, i) => (
              <Bubble key={i} m={m} />
            ))}
            {pending && (
              <div style={{ ...assistantBubble, opacity: 0.55 }}>
                <span style={{ letterSpacing: 2 }}>···</span>
              </div>
            )}
          </div>

          <form
            style={panelInputRow}
            onSubmit={(e) => {
              e.preventDefault();
              void send(input);
            }}
          >
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="说点什么…"
              disabled={pending}
              style={panelInput}
              autoFocus
            />
            <button
              type="submit"
              disabled={pending || !input.trim()}
              aria-label="发送"
              style={panelSendBtn}
            >
              <SendArrow />
            </button>
          </form>
        </div>
      )}

      {/* pet 浮窗 —— 液态光点：anchor 是一个挂点，从这里垂下绳子 + 光点。
          - attached 模式（找到封面 + 不在歌词页）：anchor 贴到封面右下角
          - free 模式（无封面 / 歌词页）：anchor 在屏幕右下角附近
          两种模式共用 left/top inline + CSS transition，模式切换时跑平滑移动动画。
          钟摆围绕 anchor 自身（绳子顶端）做缓慢摆动。 */}
      <div
        className={`claudio-pet-anchor${coverHook ? " is-attached" : ""}`}
        style={
          {
            // anchorPos 在 mount 后第一次 measure 才有值，第一帧用 fallback
            // 避免视觉上闪烁
            left: anchorPos?.x ?? -9999,
            top: anchorPos?.y ?? -9999,
            "--orb-rgb": `${orbRgb[0]}, ${orbRgb[1]}, ${orbRgb[2]}`,
            "--orb-rgb-light": `${orbRgbLight[0]}, ${orbRgbLight[1]}, ${orbRgbLight[2]}`,
          } as CSSProperties
        }
        aria-hidden={false}
      >
        <button
          ref={petBtnRef}
          className={`claudio-pet${open ? " is-open" : ""}${player.isPlaying ? " is-playing" : ""}`}
          onClick={() => {
            setHint(null);
            if (open) requestClose();
            else {
              setClosing(false);
              setOpen(true);
            }
          }}
          aria-label={open ? "关闭 Claudio" : "打开 Claudio"}
        >
          <span className="claudio-pet__core" aria-hidden>
            {/* logo 直接做球面：morph 形状裁，叠 specular 高光 + 内阴影做液感 */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              className="claudio-pet__core-img"
              src="/icon-192.png"
              alt=""
              draggable={false}
            />
          </span>
        </button>
      </div>
    </>
  );
}

function SendArrow() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      style={{ display: "block" }}
    >
      {/* 一支上指箭头 —— 类似 iMessage 的发送 icon */}
      <line x1="12" y1="19" x2="12" y2="5" />
      <polyline points="6 11 12 5 18 11" />
    </svg>
  );
}

function Bubble({ m }: { m: ChatMessage }) {
  const isUser = m.role === "user";
  return (
    <div style={isUser ? userBubble : assistantBubble}>
      <div>{m.text}</div>
      {m.play && (
        <div style={playFooter}>
          ▶ 已为你排 {m.play.trackIds.length} 首 · {m.play.reason}
        </div>
      )}
    </div>
  );
}

function randomRange(min: number, max: number) {
  return min + Math.random() * (max - min);
}

// ============== styles ==============

// 静态部分（外观）—— 位置由 computeHintBubblePosition() 单独算
const hintBubble: CSSProperties = {
  position: "fixed",
  // 跟 .claudio-pet-anchor 同步：高于沉浸式歌词覆盖层
  zIndex: 9101,
  maxWidth: 240,
  padding: "10px 14px 11px",
  background: "rgba(20,22,28,0.62)",
  backdropFilter: "blur(28px) saturate(150%)",
  WebkitBackdropFilter: "blur(28px) saturate(150%)",
  border: "1px solid rgba(255,255,255,0.08)",
  color: "rgba(245,247,255,0.92)",
  fontSize: 13,
  letterSpacing: 0.1,
  lineHeight: 1.5,
  boxShadow:
    "inset 0 1px 0 rgba(255,255,255,0.08), 0 18px 48px rgba(0,0,0,0.55), 0 6px 24px rgba(var(--orb-rgb), 0.18)",
  animation: "claudioPetHintIn 280ms cubic-bezier(0.22, 1, 0.36, 1)",
  whiteSpace: "nowrap",
};

/**
 * 根据宠物挂点位置（coverHook）算气泡的位置 + 圆角朝向。
 *
 * **默认放右边** —— 宠物挂在封面的右下角,左边都是歌曲标题/艺人,
 * 气泡放左边会盖住信息。只有当右边宽度真的不够时,才退到左边。
 *
 * - 未 attached（默认右下角浮动）: 气泡贴右下, 小圆角在右下
 */
function computeHintBubblePosition(
  coverHook: { x: number; y: number } | null,
): CSSProperties {
  if (!coverHook) {
    return {
      right: 32,
      bottom: 114,
      borderRadius: "14px 14px 4px 14px",
    };
  }
  const vw = typeof window !== "undefined" ? window.innerWidth : 1200;
  // 宠物按钮中心大致在 (coverHook.x, coverHook.y + 50). 顶部跟挂点齐平略上.
  const top = Math.max(20, coverHook.y - 6);

  // 右侧需要的空间: 32px 间距 + 气泡 maxWidth 240 + 16px 边缘余量 = ~290
  const SPACE_NEEDED = 290;
  const roomOnRight = vw - coverHook.x;

  if (roomOnRight >= SPACE_NEEDED) {
    // 默认: 气泡在宠物右侧
    return {
      left: coverHook.x + 32,
      top,
      borderRadius: "14px 14px 14px 4px", // 小角在左下指向宠物
    };
  }
  // 右侧装不下了 → 退到左边
  return {
    right: vw - coverHook.x + 32,
    top,
    borderRadius: "14px 14px 4px 14px", // 小角在右下指向宠物
  };
}



const panelHeader: CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: 10,
  padding: "12px 16px 11px",
  // 极弱玻璃分隔线，不再用 mint
  borderBottom: "1px solid rgba(255,255,255,0.05)",
};

// 标题行的小宠物核 —— 跟主光点一脉相承，颜色跟封面（用同一个 --orb-rgb）
const panelHeaderCore: CSSProperties = {
  flexShrink: 0,
  width: 10,
  height: 10,
  borderRadius: 999,
  background:
    "radial-gradient(circle at 30% 28%, rgba(255,255,255,0.85) 0%, rgba(var(--orb-rgb), 0.85) 50%, rgba(var(--orb-rgb), 0.95) 100%)",
  boxShadow:
    "inset -0.5px -0.8px 1.4px rgba(0,0,0,0.28), inset 0.4px 0.6px 1px rgba(255,255,255,0.35)",
};

const panelHeaderText: CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: "flex",
  flexDirection: "column",
  gap: 1,
};

const panelHeaderTitle: CSSProperties = {
  fontSize: 13,
  letterSpacing: 0.6,
  color: "rgba(233,239,255,0.92)",
  fontWeight: 500,
  lineHeight: 1.2,
};

const panelHeaderSubtitle: CSSProperties = {
  fontSize: 11,
  letterSpacing: 0.2,
  // 副标颜色跟封面色 —— 跟主光点 + 绳子是一脉的暖色 accent
  color: "rgba(var(--orb-rgb-light), 0.78)",
  lineHeight: 1.35,
  whiteSpace: "nowrap",
  overflow: "hidden",
  textOverflow: "ellipsis",
};

const panelCloseBtn: CSSProperties = {
  width: 24,
  height: 24,
  borderRadius: 999,
  border: "none",
  background: "transparent",
  color: "rgba(233,239,255,0.5)",
  fontSize: 18,
  lineHeight: 1,
  cursor: "pointer",
};

const panelScroll: CSSProperties = {
  flex: 1,
  overflowY: "auto",
  padding: "14px 16px",
  display: "flex",
  flexDirection: "column",
  gap: 10,
};

const emptyHintStyle: CSSProperties = {
  margin: "auto",
  textAlign: "center",
  color: "rgba(233,239,255,0.36)",
  fontSize: 13,
  lineHeight: 1.6,
  letterSpacing: 0.5,
  fontWeight: 300,
};

// 用户：玻璃深片，轻微凸起 + 顶沿微亮 —— 不抢戏，跟面板玻璃同语言
const userBubble: CSSProperties = {
  alignSelf: "flex-end",
  maxWidth: "78%",
  padding: "7px 12px",
  borderRadius: 12,
  background: "rgba(255,255,255,0.06)",
  border: "1px solid rgba(255,255,255,0.06)",
  color: "rgba(233,239,255,0.92)",
  fontSize: 13,
  lineHeight: 1.55,
  letterSpacing: 0.1,
  boxShadow:
    "inset 0 1px 0 rgba(255,255,255,0.07), 0 4px 14px rgba(0,0,0,0.28)",
};

// 助手：无实心块，文字流 + 极淡底色 —— 像旁白而不是聊天
const assistantBubble: CSSProperties = {
  alignSelf: "stretch",
  maxWidth: "100%",
  padding: "4px 4px 4px 0",
  background: "transparent",
  color: "rgba(245,247,255,0.9)",
  fontSize: 13.5,
  lineHeight: 1.7,
  letterSpacing: 0.15,
};

// 播放回执：从助手文字延伸的"动作回执"，颜色取自封面（跟主光点同步）
const playFooter: CSSProperties = {
  marginTop: 6,
  display: "inline-flex",
  alignItems: "center",
  gap: 8,
  fontSize: 11,
  letterSpacing: 0.4,
  color: "rgba(var(--orb-rgb-light), 0.85)",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};

const panelInputRow: CSSProperties = {
  display: "flex",
  alignItems: "center",
  padding: "11px 13px",
  gap: 8,
  // 极弱玻璃分隔线
  borderTop: "1px solid rgba(255,255,255,0.05)",
};

const panelInput: CSSProperties = {
  flex: 1,
  padding: "9px 14px",
  borderRadius: 999,
  border: "1px solid rgba(255,255,255,0.06)",
  background: "rgba(255,255,255,0.04)",
  color: "rgba(245,247,255,0.96)",
  fontSize: 13.5,
  letterSpacing: 0.15,
  outline: "none",
  // 微微玻璃感
  boxShadow: "inset 0 1px 0 rgba(255,255,255,0.04)",
};

// 发送按钮：跟主光点是同一个语言 —— 玻璃球，封面色，顶沿一道亮线
const panelSendBtn: CSSProperties = {
  width: 32,
  height: 32,
  padding: 0,
  borderRadius: 999,
  border: "none",
  background:
    "radial-gradient(circle at 30% 28%, rgba(255,255,255,0.5) 0%, rgba(var(--orb-rgb-light), 0.92) 55%, rgba(var(--orb-rgb), 0.95) 100%)",
  color: "rgba(20,22,28,0.78)",
  cursor: "pointer",
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  boxShadow:
    "inset 0 1px 0 rgba(255,255,255,0.5), inset 0 -1px 1.5px rgba(0,0,0,0.2), 0 4px 12px rgba(0,0,0,0.32), 0 2px 6px rgba(var(--orb-rgb), 0.32)",
  transition:
    "transform 120ms ease, box-shadow 200ms ease, filter 200ms ease",
  flexShrink: 0,
};

// ============== 封面取色 ==============
//
// 把封面图缩到 24×24 抽样，过滤掉灰、过暗、过曝的像素，剩下的"鲜活像素"
// 取均值作为水珠主色。CDN 代理已经回 `Access-Control-Allow-Origin: *`，
// 配合 crossOrigin='anonymous' 拉的 <img>，canvas 不会被 taint。
//
// 失败回落到默认薄荷绿。结果不缓存：每次切歌走一次，开销可忽略。
async function sampleVividColor(
  url: string,
): Promise<[number, number, number]> {
  if (!url) throw new Error("empty url");
  const img = new Image();
  img.crossOrigin = "anonymous";
  const loaded = new Promise<void>((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = () => reject(new Error("image load error"));
  });
  img.src = url;
  await loaded;

  const SIZE = 24;
  const canvas = document.createElement("canvas");
  canvas.width = SIZE;
  canvas.height = SIZE;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("no 2d ctx");
  ctx.drawImage(img, 0, 0, SIZE, SIZE);
  const { data } = ctx.getImageData(0, 0, SIZE, SIZE);

  let rs = 0,
    gs = 0,
    bs = 0,
    n = 0;
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const a = data[i + 3];
    if (a < 200) continue;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const sat = max === 0 ? 0 : (max - min) / max;
    const val = max / 255;
    if (sat < 0.18) continue; // 过灰
    if (val < 0.18 || val > 0.96) continue; // 过暗 / 过曝
    rs += r;
    gs += g;
    bs += b;
    n++;
  }
  if (n === 0) {
    // 兜底：纯均值
    for (let i = 0; i < data.length; i += 4) {
      rs += data[i];
      gs += data[i + 1];
      bs += data[i + 2];
      n++;
    }
  }
  if (n === 0) throw new Error("no usable pixels");
  // 略提一点饱和：从亮度往两侧拉，避免抽完是个发灰的中间色
  let r = rs / n;
  let g = gs / n;
  let b = bs / n;
  const lum = 0.299 * r + 0.587 * g + 0.114 * b;
  const boost = 1.18;
  r = clamp255(lum + (r - lum) * boost);
  g = clamp255(lum + (g - lum) * boost);
  b = clamp255(lum + (b - lum) * boost);
  return [Math.round(r), Math.round(g), Math.round(b)];
}

function clamp255(v: number): number {
  return Math.max(0, Math.min(255, v));
}

/**
 * 算封面整体平均亮度（0..1，BT.709 加权）。
 * 给 Windows decorum 三键决定用浅图标还是深图标。
 *
 * 不过滤"鲜活像素"，整张图均值 —— 我们要的是"画面整体是亮还是暗"，
 * 不是主色调。
 */
async function sampleAvgLuminance(url: string): Promise<number> {
  if (!url) throw new Error("empty url");
  const img = new Image();
  img.crossOrigin = "anonymous";
  const loaded = new Promise<void>((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = () => reject(new Error("image load error"));
  });
  img.src = url;
  await loaded;

  const SIZE = 16;
  const canvas = document.createElement("canvas");
  canvas.width = SIZE;
  canvas.height = SIZE;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("no 2d ctx");
  ctx.drawImage(img, 0, 0, SIZE, SIZE);
  const { data } = ctx.getImageData(0, 0, SIZE, SIZE);
  let lumSum = 0;
  let n = 0;
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] < 200) continue;
    // BT.709 系数对人眼亮度感最贴
    const lum = (0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2]) / 255;
    lumSum += lum;
    n++;
  }
  return n === 0 ? 0.5 : lumSum / n;
}

/**
 * 把 Windows decorum 三键的前景色写到 <html>。CSS 端读 `--titlebar-fg`
 * + `--titlebar-fg-hover` 两个变量去染色。
 *
 * isLightBg=true（封面浅）→ 用接近黑的图标
 * isLightBg=false（默认深底）→ 用浅图标
 */
function writeTitlebarFg(isLightBg: boolean) {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  if (isLightBg) {
    root.style.setProperty("--titlebar-fg", "rgba(20, 22, 28, 0.78)");
    root.style.setProperty("--titlebar-fg-hover", "rgba(20, 22, 28, 1)");
  } else {
    root.style.setProperty("--titlebar-fg", "rgba(245, 247, 255, 0.85)");
    root.style.setProperty("--titlebar-fg-hover", "rgba(245, 247, 255, 1)");
  }
}

// 把颜色往白色拉 t (0..1)。t=0 不变，t=1 全白。
function lightenTowardWhite(
  rgb: [number, number, number],
  t: number,
): [number, number, number] {
  const f = Math.max(0, Math.min(1, t));
  return [
    Math.round(rgb[0] + (255 - rgb[0]) * f),
    Math.round(rgb[1] + (255 - rgb[1]) * f),
    Math.round(rgb[2] + (255 - rgb[2]) * f),
  ];
}
