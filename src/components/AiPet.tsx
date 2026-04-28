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
  type ChatMessage,
} from "@/lib/pet-agent";

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
  // 封面右下挂点（视口坐标）—— 找不到封面则回退到右下角
  const [coverHook, setCoverHook] = useState<{ x: number; y: number } | null>(
    null,
  );
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
  // 春风风场（v2）—— 弹簧-阻尼物理 + 冲量阵风
  //
  // 上一版用 lerp 追一个抖动的目标，每帧追不上就出现"卡顿"的视觉错觉，
  // 加上 drop-shadow 每帧重画，渲染开销很大。本版的思路：
  //
  //   1. 角度走真正的弹簧-阻尼系统（k=刚度 / c=阻尼），物理积分天然顺滑，
  //      也有真实物体的"摆-回"反弹感。
  //   2. 持续风力 = 三层低频正弦（频率非整数倍，避免规律性），振幅按平滑后的
  //      music amp 缩放。
  //   3. 阵风 = 一次性"冲量"加给 velocity，让弹簧自己 rebound，比"包络曲线"
  //      自然得多。
  //   4. amp 用 EMA 平滑（半衰期 ~150ms），避免 frame-by-frame 的 RMS 抖动
  //      传到视觉上。
  //   5. CSS 端只读两个变量：--amp（颜色微调）和 --wind-rotate（摆动）。
  //      抛弃 driftX/driftY/shadow vars/动态 morph-duration，把每帧的 paint
  //      压回到只重排 transform 一次。
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
    let ampSmooth = 0.18;
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

      const rawAmp =
        (window as unknown as { __claudioAmp?: number }).__claudioAmp ?? 0.18;
      // [0.15, 0.95] → [0, 1]
      const norm = Math.max(0, Math.min(1, (rawAmp - 0.15) / 0.8));
      // EMA 平滑：1 - exp(-dt/τ)；τ ≈ 0.2s 给出 ~150ms 半衰期
      const ampLerp = 1 - Math.exp(-dt / 0.2);
      ampSmooth += (norm - ampSmooth) * ampLerp;

      const isAttached = anchor.classList.contains("is-attached");
      const motionScale = reduceMotion ? 0.2 : isAttached ? 1 : 0.85;

      // 持续风力：三层低频，频率挑了非整数倍 (0.18 / 0.31 / 0.49 Hz)，
      // 看着像"风一阵阵地吹"，没有可感知的循环
      const t = now / 1000;
      const breeze =
        (Math.sin(t * 0.18 + phaseA) * 1.0 +
          Math.sin(t * 0.31 + phaseB) * 0.55 +
          Math.sin(t * 0.49 + phaseC) * 0.3) *
        (0.7 + ampSmooth * 1.6);

      // 阵风冲量：到时间就给 velocity 一拍，弹簧自己 rebound
      if (now >= nextGustAt) {
        const musicLift = 0.8 + ampSmooth * 1.7;
        const impulseMag =
          randomRange(4.5, 9.0) * musicLift * motionScale;
        const impulseDir = Math.random() > 0.5 ? 1 : -1;
        velocity += impulseMag * impulseDir;
        // 音乐越激情，下一拍来得越快
        nextGustAt =
          now + randomRange(1800, 5400) - ampSmooth * 1600;
      }

      // 弹簧-阻尼积分：F = k(target - x) - c·v
      const target = breeze * motionScale * 2.0;
      const accel = (target - angle) * SPRING_K - velocity * SPRING_C;
      velocity += accel * dt;
      angle += velocity * dt;

      // 物理层硬上限，防止极端冲量打飞 —— 给到 ±12° 让大风也不会甩飞
      if (angle > 12) {
        angle = 12;
        if (velocity > 0) velocity = 0;
      } else if (angle < -12) {
        angle = -12;
        if (velocity < 0) velocity = 0;
      }

      anchor.style.setProperty("--amp", ampSmooth.toFixed(3));
      anchor.style.setProperty("--wind-rotate", `${angle.toFixed(2)}deg`);

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
    return () => {
      cancelled = true;
    };
  }, [coverUrl]);

  // 跟踪封面右下角位置，让光点真的"挂在封面上"
  useEffect(() => {
    let lastX = -1;
    let lastY = -1;
    const measure = () => {
      const cover = document.querySelector<HTMLElement>("[data-claudio-cover]");
      if (!cover) {
        if (lastX !== -1 || lastY !== -1) {
          lastX = -1;
          lastY = -1;
          setCoverHook(null);
        }
        return;
      }
      const r = cover.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) return;
      // 挂在封面右下角内侧 14px、底边线上 —— 让绳子从封面下边框右侧出来
      const x = r.right - 14;
      const y = r.bottom;
      if (Math.abs(x - lastX) < 0.5 && Math.abs(y - lastY) < 0.5) return;
      lastX = x;
      lastY = y;
      setCoverHook({ x, y });
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
        setHint(message);
        // 5s 后自动收
        window.setTimeout(() => setHint(null), 5500);
      } catch (e) {
        console.debug("[claudio] pet daily greeting 失败", e);
      }
    })();
  }, []);

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
          text: res.text || (res.play ? "好，给你排一组" : "嗯。"),
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
      {/* 招呼气泡（只在 pet 上方，自动收）—— 玻璃 + 封面色辉 */}
      {hint && !open && (
        <div
          style={
            {
              ...hintBubble,
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
                    : "可以直接说想听什么"}
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
              <div style={emptyHint}>
                跟我说一句吧 ——<br />
                "工作听，旋律抓耳不要太吵" / "陪我熬夜" / "走路的时候听"
              </div>
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
              placeholder="想听点什么…"
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
          有封面时 anchor 贴到封面右下角；否则回退到屏幕右下角。
          钟摆围绕 anchor 自身（绳子顶端）做缓慢摆动。 */}
      <div
        className={`claudio-pet-anchor${coverHook ? " is-attached" : ""}`}
        style={
          {
            ...(coverHook
              ? {
                  left: coverHook.x,
                  top: coverHook.y,
                  right: "auto",
                  bottom: "auto",
                }
              : null),
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

const hintBubble: CSSProperties = {
  position: "fixed",
  right: 32,
  bottom: 114,
  zIndex: 60,
  maxWidth: 300,
  padding: "11px 15px 12px",
  borderRadius: "16px 16px 4px 16px",
  background: "rgba(20,22,28,0.62)",
  backdropFilter: "blur(28px) saturate(150%)",
  WebkitBackdropFilter: "blur(28px) saturate(150%)",
  border: "1px solid rgba(255,255,255,0.08)",
  color: "rgba(245,247,255,0.92)",
  fontSize: 13,
  letterSpacing: 0.1,
  lineHeight: 1.55,
  boxShadow:
    "inset 0 1px 0 rgba(255,255,255,0.08), 0 18px 48px rgba(0,0,0,0.55), 0 6px 24px rgba(var(--orb-rgb), 0.18)",
  animation: "claudioPetHintIn 360ms cubic-bezier(0.22, 1, 0.36, 1)",
};



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

const emptyHint: CSSProperties = {
  margin: "auto",
  textAlign: "center",
  color: "rgba(233,239,255,0.48)",
  fontSize: 12.5,
  lineHeight: 1.85,
  letterSpacing: 0.2,
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
