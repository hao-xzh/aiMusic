package app.pipo.nativeapp.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token —— 严格对照 src/app/globals.css 与 PlayerCard / AiPet 内的常量。
 * 任何新视觉差量先回到这里加常量，避免散落硬编码。
 */
object PipoColors {
    // globals.css :root
    val Bg0 = Color(0xFF05060A)             // --bg-0：body 兜底纯黑
    val Bg1 = Color(0xFF0A0D14)             // --bg-1
    val Ink = Color(0xFFE9EFFF)             // --ink
    val InkDim = Color(0xFF8A93A8)          // --ink-dim
    val Accent = Color(0xFF9BE3C6)          // --accent

    // 兼容老命名
    val Background = Bg0
    val Surface = Bg1
    val Text = Ink
    val TextMuted = Color(0xCCE9EFFF)
    val TextDim = Color(0x66E9EFFF)
    val Mint = Accent
    val Blue = Color(0xFF9BB7FF)
    val Gold = Color(0xFFF3C46A)

    // glass 卡（沉浸式控件容器）
    val GlassFill = Color(0x8C0C1018)       // rgba(12,16,24,0.55)
    val GlassStroke = Color(0x14E9EFFF)     // rgba(233,239,255,0.08)

    // 进度条 / 控件背景层
    val ProgressTrack = Color(0x33E9EFFF)
    val ProgressFill = Color(0xFFE9EFFF)
    val IconBgFaint = Color(0x14FFFFFF)
}

object PipoTypography {
    // PlayerCard.tsx compact 设计 token：
    //   COVER_SIZE = min(clamp(220px, 86vw, 400px), 50vh)
    //   TITLE_FS = clamp(17px, 4vw, 22px)
    //   SUBTITLE_FS = clamp(12px, 3.2vw, 14px)
    //   LYRIC_BOX_H = clamp(116px, 15vh, 150px)
    //   LYRIC_ROW_H = clamp(26px, 3.6vh, 32px)
    //   LYRIC_ACTIVE_FS = clamp(16px, 4.2vw, 19px)
    //   LYRIC_DIM_FS = clamp(11px, 2.8vw, 13px)
    val Title = 19.sp
    val Subtitle = 13.sp
    val Mono = 12.sp
    val LyricActive = 18.sp
    val LyricDim = 12.sp
    val ImmersiveLyricFs = 28.sp
    val ImmersiveTitle = 22.sp
    val ImmersiveTitleLarge = 28.sp
    val ImmersiveSubtitle = 14.sp
}

object PipoMotion {
    // 沉浸式 FLIP 曲线 / 时长（PlayerCard.tsx）
    val FlipDurationMs = 460
    val CloseDurationMs = 360
    // cubic-bezier(0.32, 0.72, 0, 1)
    val FlipEase: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    // cubic-bezier(0.6, 0.04, 0.22, 1)
    val CloseEase: Easing = CubicBezierEasing(0.6f, 0.04f, 0.22f, 1f)

    // AdaptiveDotField cross-fade
    val CoverFadeMs = 1100
    // pageEnter / mount fade
    val PageEnterMs = 100

    // listFade / metaFade
    val MetaFadeMs = 380
    val ListFadeMs = 500
}

object PipoDimens {
    // PlayerCard compact
    val CompactCoverMaxDp = 400.dp
    val CompactCoverMinDp = 220.dp
    val CompactCornerDp = 12.dp
    val CompactProgressBarHeight = 3.dp
    val CompactProgressTopPad = 28.dp
    val CompactControlsTopPad = 30.dp
    val NavIconSize = 22.dp
    val PlayButtonSize = 64.dp
    val SkipButtonSize = 48.dp

    // immersive
    val ImmersiveLyricRowHeight = 70.dp
    val ImmersiveCoverCornerDp = 0.dp
    val ImmersiveTitlePadX = 38.dp

    // AiPet idle
    val PetSize = 48.dp
    val PetChatWidth = 360.dp
    val PetChatHeight = 500.dp
}
