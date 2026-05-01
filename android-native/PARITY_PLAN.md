# Pipo — 原生 Android 全量复刻计划

> 目标：把 `android-native/` 改造成与 `src/`（Tauri / Next.js / React）**功能、样式、动画 1:1**
> 的原生 Android 项目。可在真机上安装运行。

源端代码体量（参考）：

| 模块 | 行数 | 说明 |
|---|---|---|
| `src/components/PlayerCard.tsx` | 2090 | 主播放卡片，含沉浸式歌词 FLIP 动画 |
| `src/components/AiPet.tsx` + `AiPet.css` | 1124 + 341 | 右下浮窗 AI 宠物 |
| `src/app/distill/page.tsx` | 1492 | 歌单蒸馏页 |
| `src/app/settings/page.tsx` | 1250 | 设置页 |
| `src/app/taste/page.tsx` | 758 | 口味页 |
| `src/app/login/page.tsx` | 605 | 登录页（QR + 短信） |
| `src/lib/player-state.tsx` | 1429 | 全局播放状态 + 续杯 + Native bridge |
| `src/lib/audio-scheduler.ts` | 558 | WebAudio 采样级排程 |
| `src/lib/audio-analysis.ts` | 557 | 节奏 / 能量分析 |
| `src/lib/gapless-engine.ts` | 541 | mp3/aac padding 切除 |
| `src/lib/music-intent.ts` | 528 | 自然语言曲库意图 |
| `src/lib/taste-profile.ts` | 521 | 口味画像聚合 |
| `src/lib/pet-agent.ts` | 660 | AI 宠物 agent + tool calling |
| `src/lib/mix-planner.ts` / `transition-*.ts` / `candidate-*.ts` / `discovery.ts` | ~2200 | 接歌 + 召回 + 排序 |
| `src/lib/cover-color.ts` / `lrc.ts` / `yrc.ts` / `cdn.ts` | ~400 | 视觉辅助 / 歌词解析 / CDN |
| `src/components/DotField.tsx` / `AdaptiveDotField.tsx` | 273 + 94 | 全屏粒子流 + 封面驱动背景 |
| `src/components/PlaylistFusionBg.tsx` / `PlaylistPager.tsx` / `Waveform.tsx` 等 | ~700 | 辅助视觉 |

总计约 18,873 行（含注释）。

---

## 架构原则

1. **UI 100% Compose**，不再混用任何 WebView。
2. **音频走 Media3 ExoPlayer + MediaSession**，gapless 用 `setPlaybackParameters` + `addMediaSources` 链；
   不复刻 WebAudio 采样级 scheduler（API 不存在 1:1 对应；用 ExoPlayer 自己的预缓冲 + 准确 seek）。
3. **网易云 / AI / 音频缓存** 走 `RustBridgeRepository` 调 `libpipo_native_bridge.so`；JNI 接口
   保持与 `src-tauri/src/{netease,ai,audio}` 对齐。
4. **设计 token、颜色采样、文字色判断、动画曲线** 全部对照 `globals.css` 与各组件内常量，
   按 dp / sp 等 Android 单位转换。
5. **导航**：完全摒弃底部 tab 栏（这是当前 native 跟 React 对不齐的最大点）。改成
   `PlayerScreen` 内底栏三图标（歌词 / 歌单 / 设置）→ `Distill` / `Settings`，
   `Taste`、`Login` 走子路由 push，`AiPet` 是全局 overlay。

---

## 切片清单（按依赖顺序）

每个 slice 完成后都应能 `./gradlew :app:assembleDebug` 出可装机 apk。

### Slice 0 ✅（本次启动）
- Gradle wrapper 安装 (`gradlew`, `gradle/wrapper/*`)。
- `PARITY_PLAN.md`（本文件）。
- TodoWrite 跟踪。

### Slice 1 — 设计基座 + 全局背景
- `PipoDesign.kt`：从 `globals.css` 复刻完整 token 集（Background / Card / Ink /
  Accent / 字号梯度 / 动画曲线常量）。
- `PipoDarkColors`：在 `MaterialTheme` 上挂全套 darkColorScheme。
- `DotField.kt`：300 颗粒子，2D flow field（Σ sin/cos 三层叠加），边缘 vignette 衰减，
  amp 驱动流速 + 半径 + 透明度。Composable 暴露 `playing: Boolean` 与全局 `claudioAmp` 入口。
- `AdaptiveDotField.kt`：当前曲目封面 → blur + saturate 全屏铺底 + 渐变压暗 +
  DotField 叠加；曲目切换 1100ms cross-fade。

### Slice 2 — 主播放卡片 (PlayerCard)
- `PlayerCard.kt` 取代当前 `PlayerScreen.kt`：
  - **compact 布局**：封面 (clamp 尺寸) / 标题 / 副标题 / 进度条 / -剩余 / 三键控制 /
    底栏三图标（歌词 / 歌单 / 设置），完全对齐 `PlayerCard.tsx` 1-252 行。
  - **immersive overlay**：按 `ImmersiveLyrics` 的 FLIP 动画 —— 封面 size/position
    用 `animateFloatAsState` + bias，背景 = 同源封面 blur 140 + 渐变叠加；歌词 7 行，
    YRC 字符级 wipe。曲线 `cubic-bezier(0.32, 0.72, 0, 1)` / 620ms 开 / 540ms 收。
  - YRC + LRC 解析：`YrcParser.kt` 已存在，扩展 LRC 解析与二分定位。
  - 封面边缘色采样：`CoverColor.kt` 用 Coil → ImageBitmap → 顶/底/右 5px 平均 RGB →
    luma 阈值 145 → tone。
- 删除当前 `PipoNativeApp.kt` 的底部 tab 栏，改为**单根 Player + Overlay 模型**，
  路由用 `Navigation` compose 库或简单 `mutableState<Screen>`。

### Slice 3 — AiPet
- 右下浮窗 → 点击展开聊天面板。
- 风场 + 节拍包络（amp 驱动 rotate / translateY / scale），呼吸光晕（pulse keyframe）。
- 锚点切换：`attached`（封面右下内侧）↔ `free`（屏幕右下）随 immersive overlay 状态
  在 600ms 内补间。
- 招呼气泡（每天首次 5s 自动收）。
- `pet-agent` Kotlin 镜像：调 `RustBridgeRepository.aiChat(history, userText)`
  完成 tool-calling 流。

### Slice 4 — 四屏（Distill / Taste / Login / Settings）
- 视觉对照各自 `page.tsx`：
  - distill：source / candidate / smoothness 卡片网格 + 歌单列表 + pipeline 步骤。
  - taste：能量 / 温度 / 新颖度三轴 orb + tag 列表。
  - login：QR + 手机号短信表单。
  - settings：分组 (Music source / AI / Playback / Appearance / About)，其中
    Audio cache / AI provider / 用户事实 与 React 对照。
- 统一 `BackButton` / `safe-top` padding 行为。

### Slice 5 — 音频引擎
- `MediaSession` 全套（已部分实现）。
- gapless：使用 ExoPlayer `MediaItem.Builder().setClippingConfiguration` 切掉 padding；
  接歌靠 `addMediaItem` + 预缓冲。
- 续杯：`ContinuousQueueSource` Kotlin 接口 → AI / Discovery 双源。
- 行为日志：`logBehavior` 写入本地 + JNI 同步。

### Slice 6 — 推荐 / 语义层
- `taste-profile.ts` → `TasteAggregator.kt`：从行为日志统计 5 维口味。
- `mix-planner.ts` + `transition-judge.ts` → `MixPlanner.kt`：调 AI 接歌判断。
- `candidate-recall.ts` + `discovery.ts` → `Discovery.kt`：召回。
- `semantic-recall.ts` 走 Rust JNI（Embeddings 在 Rust 侧）。

---

## 验收 / 验证

每完成一个 slice：

1. `cd android-native && ./gradlew :app:assembleDebug`
2. 产物：`app/build/outputs/apk/debug/app-debug.apk`
3. `adb install -r ...`，对照 React 版逐屏走查样式 / 动画。
4. 不达标的视觉点回到 PARITY_PLAN.md 加上 ⚠️，直到所有 ⚠️ 清零。

---

## 维护
- 任何 React 端侧改动需要 mirror 到原生端。本目录 README 加入 mirror 责任清单。
- 新增 token / 动画必须先回到 `PipoDesign.kt`，避免散落硬编码。
