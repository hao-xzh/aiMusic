package app.pipo.nativeapp.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * SVG 字形 —— 路径数据 1:1 对照 src/components/PlayerCard.tsx 1716-2089 行的 inline <path>。
 * 改用 ImageVector + path DSL，让 Compose 自己的矢量渲染管线处理填充 / 描边 /
 * line-join，避免之前 Canvas 手画的尺寸 / 圆角 / 描边权重偏差。
 */

// ---------- 缓存 ImageVector（每个 glyph 只在首次访问时构造一次） ----------

private val PlayIconVector: ImageVector by lazy {
    iconVector {
        // <path d="M8 5.5v13l11-6.5z" /> + fill=current + stroke=current strokeWidth=2.4 strokeLinejoin=round
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8f, 5.5f)
            verticalLineToRelative(13f)
            lineToRelative(11f, -6.5f)
            close()
        }
    }
}

private val PauseIconVector: ImageVector by lazy {
    iconVector {
        // <rect x="6" y="4.5" width="4.2" height="15" rx="1.6" />
        path(fill = SolidColor(Color.White)) {
            moveTo(7.6f, 4.5f); horizontalLineTo(8.6f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, 1.6f)
            verticalLineTo(17.9f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, 1.6f)
            horizontalLineTo(7.6f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, -1.6f)
            verticalLineTo(6.1f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, -1.6f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(15.4f, 4.5f); horizontalLineTo(16.4f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, 1.6f)
            verticalLineTo(17.9f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, 1.6f)
            horizontalLineTo(15.4f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, -1.6f)
            verticalLineTo(6.1f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, -1.6f)
            close()
        }
    }
}

private val SkipBackIconVector: ImageVector by lazy {
    iconVector {
        // <rect x="3.6" y="6.5" width="1.8" height="11" rx="0.9" stroke="none" />
        path(fill = SolidColor(Color.White)) {
            moveTo(4.5f, 6.5f); horizontalLineTo(4.5f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, 0.9f, 0.9f)
            verticalLineTo(16.6f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, -0.9f, 0.9f)
            horizontalLineTo(4.5f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, -0.9f, -0.9f)
            verticalLineTo(7.4f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, 0.9f, -0.9f)
            close()
        }
        // <path d="M13 7.2L7 11.5v1L13 16.8z" /> stroke 2.2 round join
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(13f, 7.2f)
            lineTo(7f, 11.5f)
            verticalLineToRelative(1f)
            lineTo(13f, 16.8f)
            close()
        }
        // <path d="M21 7.2l-6 4.3v1l6 4.3z" />
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(21f, 7.2f)
            lineToRelative(-6f, 4.3f)
            verticalLineToRelative(1f)
            lineToRelative(6f, 4.3f)
            close()
        }
    }
}

private val SkipForwardIconVector: ImageVector by lazy {
    iconVector {
        // <path d="M3 7.2l6 4.3v1l-6 4.3z" />
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3f, 7.2f)
            lineToRelative(6f, 4.3f)
            verticalLineToRelative(1f)
            lineToRelative(-6f, 4.3f)
            close()
        }
        // <path d="M11 7.2l6 4.3v1l-6 4.3z" />
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(11f, 7.2f)
            lineToRelative(6f, 4.3f)
            verticalLineToRelative(1f)
            lineToRelative(-6f, 4.3f)
            close()
        }
        // <rect x="18.6" y="6.5" width="1.8" height="11" rx="0.9" stroke="none" />
        path(fill = SolidColor(Color.White)) {
            moveTo(19.5f, 6.5f); horizontalLineTo(19.5f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, 0.9f, 0.9f)
            verticalLineTo(16.6f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, -0.9f, 0.9f)
            horizontalLineTo(19.5f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, -0.9f, -0.9f)
            verticalLineTo(7.4f)
            arcToRelative(0.9f, 0.9f, 0f, false, true, 0.9f, -0.9f)
            close()
        }
    }
}

private val LyricsIconVector: ImageVector by lazy {
    iconVector {
        // <rect x="3" y="5" width="18" height="14" rx="2.5" />
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5.5f, 5f); horizontalLineTo(18.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, 2.5f)
            verticalLineTo(16.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
            horizontalLineTo(5.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, -2.5f)
            verticalLineTo(7.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, -2.5f)
            close()
        }
        // M7 10.5h6.5
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7f, 10.5f); horizontalLineToRelative(6.5f)
        }
        // M7 14.5h9
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7f, 14.5f); horizontalLineToRelative(9f)
        }
    }
}

private val ListIconVector: ImageVector by lazy {
    iconVector {
        // M3.5 6h11
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(3.5f, 6f); horizontalLineToRelative(11f) }
        // M3.5 12h11
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(3.5f, 12f); horizontalLineToRelative(11f) }
        // M3.5 18h7
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(3.5f, 18f); horizontalLineToRelative(7f) }
        // M19.5 4v9 —— 钉子的"主轴"
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { moveTo(19.5f, 4f); verticalLineToRelative(9f) }
        // <ellipse cx="17.7" cy="14" rx="2.2" ry="1.6" fill="currentColor" stroke="none" />
        path(fill = SolidColor(Color.White)) {
            moveTo(17.7f, 12.4f)
            arcToRelative(2.2f, 1.6f, 0f, true, true, 0f, 3.2f)
            arcToRelative(2.2f, 1.6f, 0f, true, true, 0f, -3.2f)
            close()
        }
    }
}

private val LocateCurrentIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(20f, 12f)
            arcToRelative(8f, 8f, 0f, true, true, -16f, 0f)
            arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
            close()
            moveTo(12f, 2.75f); verticalLineToRelative(2.5f)
            moveTo(12f, 18.75f); verticalLineToRelative(2.5f)
            moveTo(2.75f, 12f); horizontalLineToRelative(2.5f)
            moveTo(18.75f, 12f); horizontalLineToRelative(2.5f)
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(14.15f, 12f)
            arcToRelative(2.15f, 2.15f, 0f, true, true, -4.3f, 0f)
            arcToRelative(2.15f, 2.15f, 0f, true, true, 4.3f, 0f)
            close()
        }
    }
}

private val OrderModeIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5f, 6.5f); horizontalLineToRelative(9.4f)
            moveTo(5f, 12f); horizontalLineToRelative(8f)
            moveTo(5f, 17.5f); horizontalLineToRelative(6.2f)
            moveTo(17.8f, 5.4f); verticalLineTo(18.6f)
            moveTo(14.8f, 15.8f); lineTo(17.8f, 18.6f); lineTo(20.8f, 15.8f)
        }
    }
}

/**
 * 真正的 Lucide Settings 齿轮（与 React `<NavGearIcon>` 路径 1:1）。
 * 8 颗"鼓出来的圆齿"，中间一个 r=3 的圆。
 */
// 蒸馏火花 —— 中心十字 + 4 个方向小三角，类似 Apple "Sparkles"
private val SparkIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 大十字
            moveTo(12f, 3f); verticalLineToRelative(6f)
            moveTo(12f, 15f); verticalLineToRelative(6f)
            moveTo(3f, 12f); horizontalLineToRelative(6f)
            moveTo(15f, 12f); horizontalLineToRelative(6f)
            // 斜十字（小一点）
            moveTo(5.5f, 5.5f); lineToRelative(3f, 3f)
            moveTo(15.5f, 15.5f); lineToRelative(3f, 3f)
            moveTo(5.5f, 18.5f); lineToRelative(3f, -3f)
            moveTo(15.5f, 8.5f); lineToRelative(3f, -3f)
        }
        path(fill = SolidColor(Color.White)) {
            // 中心圆点
            moveTo(13f, 12f)
            arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
            close()
        }
    }
}

// 头像剪影 —— 头 + 肩
private val ProfileIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 头：圆形 r=4 中心 (12, 8)
            moveTo(16f, 8f)
            arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
            arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
            close()
            // 肩弧线：M4 21 a 8 8 0 0 1 16 0
            moveTo(4f, 21f)
            arcToRelative(8f, 8f, 0f, false, true, 16f, 0f)
        }
    }
}

private val GearIconVector: ImageVector by lazy {
    iconVector {
        // 中心圆 <circle cx="12" cy="12" r="3" />
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(15f, 12f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            close()
        }
        // 齿轮主轮廓 —— 直接对照 React PlayerCard.tsx 2086 行那一长串 d 属性的关键节点
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(19.4f, 15f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, 1.82f)
            lineToRelative(0.06f, 0.06f)
            arcToRelative(2f, 2f, 0f, false, true, -2.83f, 2.83f)
            lineToRelative(-0.06f, -0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, -0.33f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1f, 1.51f)
            verticalLineTo(21f)
            arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
            verticalLineToRelative(-0.09f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1f, -1.51f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, 0.33f)
            lineToRelative(-0.06f, 0.06f)
            arcToRelative(2f, 2f, 0f, false, true, -2.83f, -2.83f)
            lineToRelative(0.06f, -0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, -1.82f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, -1f)
            horizontalLineTo(3f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, -4f)
            horizontalLineToRelative(0.09f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1.51f, -1f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, -1.82f)
            lineToRelative(-0.06f, -0.06f)
            arcToRelative(2f, 2f, 0f, false, true, 2.83f, -2.83f)
            lineToRelative(0.06f, 0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, 0.33f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, -1.51f)
            verticalLineTo(3f)
            arcToRelative(2f, 2f, 0f, false, true, 4f, 0f)
            verticalLineToRelative(0.09f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, 1.51f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, -0.33f)
            lineToRelative(0.06f, -0.06f)
            arcToRelative(2f, 2f, 0f, false, true, 2.83f, 2.83f)
            lineToRelative(-0.06f, 0.06f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, 1.82f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, 1.51f, 1f)
            horizontalLineTo(21f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, 4f)
            horizontalLineToRelative(-0.09f)
            arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, 1f)
            close()
        }
    }
}

private val SearchIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 圆：放大镜镜身（中心 11,11 半径 7）
            moveTo(18f, 11f)
            arcToRelative(7f, 7f, 0f, true, true, -14f, 0f)
            arcToRelative(7f, 7f, 0f, true, true, 14f, 0f)
            close()
            // 把柄
            moveTo(16.2f, 16.2f)
            lineTo(20.5f, 20.5f)
        }
    }
}

private val CloseIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }
}

// 心形（实心 = 已收藏；描边 = 未收藏）。AI 收藏 / 取消收藏结果卡用。
private val HeartFilledIconVector: ImageVector by lazy {
    iconVector {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.6f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 20.3f)
            curveTo(12f, 20.3f, 3.5f, 13.4f, 3.5f, 8.3f)
            curveTo(3.5f, 5.7f, 5.6f, 4f, 7.9f, 4f)
            curveTo(9.6f, 4f, 11.2f, 5.1f, 12f, 6.5f)
            curveTo(12.8f, 5.1f, 14.4f, 4f, 16.1f, 4f)
            curveTo(18.4f, 4f, 20.5f, 5.7f, 20.5f, 8.3f)
            curveTo(20.5f, 13.4f, 12f, 20.3f, 12f, 20.3f)
            close()
        }
    }
}

private val HeartOutlineIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 20.3f)
            curveTo(12f, 20.3f, 3.5f, 13.4f, 3.5f, 8.3f)
            curveTo(3.5f, 5.7f, 5.6f, 4f, 7.9f, 4f)
            curveTo(9.6f, 4f, 11.2f, 5.1f, 12f, 6.5f)
            curveTo(12.8f, 5.1f, 14.4f, 4f, 16.1f, 4f)
            curveTo(18.4f, 4f, 20.5f, 5.7f, 20.5f, 8.3f)
            curveTo(20.5f, 13.4f, 12f, 20.3f, 12f, 20.3f)
            close()
        }
    }
}

// 加 / 减号：加入 / 移出歌单结果卡用。
private val PlusIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12f, 5.5f); verticalLineTo(18.5f)
            moveTo(5.5f, 12f); horizontalLineTo(18.5f)
        }
    }
}

private val MinusIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(5.5f, 12f); horizontalLineTo(18.5f)
        }
    }
}

// 警示三角（叹号）：失败 / "现在没在放歌" 等错误态结果卡用。
private val AlertIconVector: ImageVector by lazy {
    iconVector {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 4.8f)
            lineTo(20.8f, 19f)
            lineTo(3.2f, 19f)
            close()
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
        ) {
            // 叹号竖线
            moveTo(12f, 10f); verticalLineTo(13.6f)
            // 叹号圆点（零长线段 + 圆头 = 实心圆点）
            moveTo(12f, 16.3f); lineTo(12f, 16.34f)
        }
    }
}

private fun iconVector(builder: ImageVector.Builder.() -> Unit): ImageVector {
    return ImageVector.Builder(
        name = "PipoGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(builder).build()
}

// ---------- 公共 Composable ----------

@Composable
fun PlayGlyph(
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(24.dp),
) {
    Icon(imageVector = PlayIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun PauseGlyph(
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(24.dp),
) {
    Icon(imageVector = PauseIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun SkipBackGlyph(
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(24.dp),
) {
    Icon(imageVector = SkipBackIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun SkipForwardGlyph(
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(24.dp),
) {
    Icon(imageVector = SkipForwardIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun LyricsIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = LyricsIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun TranslateGlyph(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = Icons.Rounded.Translate, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun ListIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = ListIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun LocateCurrentIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = LocateCurrentIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun OrderModeIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = OrderModeIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun GearIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = GearIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun SparkIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = SparkIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun ProfileIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(imageVector = ProfileIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun SearchIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(18.dp),
) {
    Icon(imageVector = SearchIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun CloseIcon(
    color: Color = Color(0xD1F5F7FF),
    modifier: Modifier = Modifier.size(14.dp),
) {
    Icon(imageVector = CloseIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun HeartGlyph(
    filled: Boolean,
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(16.dp),
) {
    Icon(
        imageVector = if (filled) HeartFilledIconVector else HeartOutlineIconVector,
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}

@Composable
fun PlaylistAddGlyph(
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(16.dp),
) {
    Icon(imageVector = PlusIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun PlaylistRemoveGlyph(
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(16.dp),
) {
    Icon(imageVector = MinusIconVector, contentDescription = null, tint = color, modifier = modifier)
}

@Composable
fun AlertGlyph(
    color: Color = Color(0xFFF5F7FF),
    modifier: Modifier = Modifier.size(16.dp),
) {
    Icon(imageVector = AlertIconVector, contentDescription = null, tint = color, modifier = modifier)
}
