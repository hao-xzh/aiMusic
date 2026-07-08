package app.pipo.nativeapp.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.pipo.nativeapp.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pipo.nativeapp.playback.PlayerViewModel
import app.pipo.nativeapp.runtime.Amp
import kotlinx.coroutines.delay

/**
 * 主播放卡片 —— 镜像 src/components/PlayerCard.tsx 的 compact 布局，按 React clamp() 1:1。
 */
@Composable
fun PlayerScreen(
    onOpenLyrics: () -> Unit,
    onOpenDistill: () -> Unit,
    onOpenSettings: () -> Unit,
    immersiveProgress: Float,
    showTranslation: Boolean,
    hasTranslation: Boolean,
    onToggleTranslation: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state = viewModel.state

    LaunchedEffect(state.isPlaying) {
        // 不在播 → 单次 reset Amp,然后退出。之前 while(true) 在暂停态也每 420ms tick
        // 一次 refreshPosition,viewmodel 走 syncFrom + 歌词比对 + 续杯检查,待机也耗电。
        // isPlaying 变 true 时 LaunchedEffect 自动 relaunch,重新进入 30Hz tick 循环。
        if (!state.isPlaying) {
            Amp.set(0f)
            viewModel.refreshPosition()
            return@LaunchedEffect
        }
        while (true) {
            viewModel.refreshPosition()
            // 33ms ≈ 30Hz —— 之前 80ms 让歌词的 per-letter 颜色 sweep 显得分级不连续
            // （短词 200ms 内只有 2~3 帧）。30Hz 让 sweep 视觉连贯。
            delay(33L)
        }
    }

    // 进度 / 位置改为在叶子(进度条、时间标签、歌词时钟)里惰性读取 viewModel.positionMs,
    // 避免高频进度让 PlayerScreen / PortraitPlayerContent 整屏 30Hz 重组。durationMs 是低频元数据。
    val durationMs = state.durationMs
    val progressProvider: () -> Float = {
        if (durationMs > 0) (viewModel.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }
    val positionProvider = remember(viewModel) { { viewModel.positionMs } }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp > configuration.screenHeightDp

    AnimatedContent(
        targetState = isLandscape,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val enter = fadeIn(
                animationSpec = tween(260, delayMillis = 70, easing = PipoMotion.FlipEase),
            ) + scaleIn(
                initialScale = 0.982f,
                animationSpec = tween(300, easing = PipoMotion.FlipEase),
            )
            val exit = fadeOut(
                animationSpec = tween(170, easing = PipoMotion.CloseEase),
            ) + scaleOut(
                targetScale = 1.018f,
                animationSpec = tween(220, easing = PipoMotion.CloseEase),
            )
            enter togetherWith exit using SizeTransform(clip = false)
        },
        label = "playerOrientation",
    ) { landscape ->
        if (landscape) {
            LandscapePlayerLyricsScreen(
                coverUrl = state.artworkUrl,
                title = state.title,
                artist = state.artist,
                album = state.album,
                trackId = state.currentTrackId,
                lyrics = state.lyrics,
                durationMs = state.durationMs,
                positionProvider = positionProvider,
                progressProvider = progressProvider,
                isPlaying = state.isPlaying,
                isLoading = state.isLoading,
                controlsEnabled = state.queue.isNotEmpty(),
                showTranslation = showTranslation,
                hasTranslation = hasTranslation,
                onToggle = viewModel::toggle,
                onNext = viewModel::next,
                onToggleTranslation = onToggleTranslation,
                onSeek = viewModel::seekTo,
                onSeekToMs = viewModel::seekToMs,
            )
        } else {
            PortraitPlayerContent(
                artworkUrl = state.artworkUrl,
                isPlaying = state.isPlaying,
                title = state.title,
                artist = state.artist,
                album = state.album,
                lyricsReady = state.lyrics.isNotEmpty(),
                queueReady = state.queue.isNotEmpty(),
                isLoading = state.isLoading,
                positionProvider = positionProvider,
                durationMs = state.durationMs,
                progressProvider = progressProvider,
                transitionProgress = immersiveProgress,
                onOpenLyrics = onOpenLyrics,
                onOpenDistill = onOpenDistill,
                onOpenSettings = onOpenSettings,
                onPrevious = viewModel::previous,
                onToggle = viewModel::toggle,
                onNext = viewModel::next,
                onSeek = viewModel::seekTo,
            )
        }
    }
}

@Composable
private fun PortraitPlayerContent(
    artworkUrl: String?,
    isPlaying: Boolean,
    title: String,
    artist: String,
    album: String,
    lyricsReady: Boolean,
    queueReady: Boolean,
    isLoading: Boolean,
    positionProvider: () -> Long,
    durationMs: Long,
    progressProvider: () -> Float,
    transitionProgress: Float,
    onOpenLyrics: () -> Unit,
    onOpenDistill: () -> Unit,
    onOpenSettings: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AppleMusicPlayerBackdrop(coverUrl = artworkUrl)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val viewportW = maxWidth
            val viewportH = maxHeight
            val controlsWidth = clampDp(260.dp, viewportW * 0.86f, 420.dp)
            val shellPad = clampDp(16.dp, viewportW * 0.04f, 40.dp)
            val titleFs = clampSp(17, viewportW.value * 0.04f, 22)
            val subtitleFs = clampSp(12, viewportW.value * 0.032f, 14)
            val controlEdges = useCoverEdgeColors(artworkUrl)
            val controlTone = toneForColor(appleMusicPureSurfaceColor(controlEdges))
            val fg = pickFg(controlTone)
            val fgDim = pickFgDim(controlTone)
            val fgFaint = if (controlTone == Tone.Dark) {
                Color(0x61000000)
            } else {
                Color(0x66FFFFFF)
            }
            val trackColor = if (controlTone == Tone.Dark) {
                Color(0x24000000)
            } else {
                Color(0x26FFFFFF)
            }
            val controlsTop = (viewportH * 0.515f)
                .coerceAtLeast(viewportW * 1.02f)
                .coerceAtMost(viewportH * 0.565f)
            // 标题块到封面 / 到进度条的距离相等 —— 都用 titleGap
            // 用户要求加高 1/2（×1.5）让封面 / 标题 / 进度条不再挤
            // 标题 → 副标题之间（titleSubGap）保持 6dp 不动
            val titleGap = clampDp(21.dp, viewportH * 0.033f, 30.dp)
            val controlsMarginTop = clampDp(22.dp, viewportH * 0.034f, 32.dp)
            val volumeMarginTop = clampDp(9.dp, viewportH * 0.014f, 14.dp)
            val navMarginTop = clampDp(12.dp, viewportH * 0.018f, 18.dp)
            val sideButtonSize = clampDp(52.dp, viewportW * 0.12f, 66.dp)
            val centerButtonSize = clampDp(66.dp, viewportW * 0.16f, 84.dp)
            val removedVolumeGap = volumeMarginTop + 30.dp + navMarginTop
            val transition = transitionProgress.coerceIn(0f, 1f)
            fun smoothRange(start: Float, end: Float): Float {
                val t = ((transition - start) / (end - start)).coerceIn(0f, 1f)
                return t * t * (3f - 2f * t)
            }
            val controlsExit = smoothRange(0.08f, 0.86f)
            val titleMove = smoothRange(0.00f, 0.62f)
            val titleExit = smoothRange(0.70f, 0.86f)
            val lyricTitleTop = immersiveTitleTop(viewportW)
            val currentTitleLeft = (viewportW - controlsWidth) * 0.5f
            val targetTitleLeft = 24.dp
            val titleScaleTarget = (22f / titleFs.value).coerceIn(1f, 1.34f)
            val titleShiftY = (lyricTitleTop - controlsTop) * titleMove
            val titleShiftX = (targetTitleLeft - currentTitleLeft) * titleMove
            val titleScale = 1f + (titleScaleTarget - 1f) * titleMove

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = shellPad)
                    .padding(top = controlsTop)
                    .widthIn(max = 600.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .width(controlsWidth)
                        .align(Alignment.CenterHorizontally),
                ) {
                    if (title.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = 1f - titleExit
                                    translationX = titleShiftX.toPx()
                                    translationY = titleShiftY.toPx()
                                    scaleX = titleScale
                                    scaleY = titleScale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                },
                        ) {
                            Text(
                                text = title,
                                color = fg,
                                style = TextStyle(
                                    fontSize = titleFs,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.sp,
                                    lineHeight = (titleFs.value * 1.25f).sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = buildString {
                                    append(artist)
                                    if (album.isNotBlank()) append(" · $album")
                                },
                                color = fgDim,
                                style = TextStyle(
                                    fontSize = subtitleFs,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Column(
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - controlsExit
                            translationY = controlsExit * 18.dp.toPx()
                        },
                    ) {
                        Spacer(modifier = Modifier.height(titleGap))
                        PipoProgressBar(
                            progress = progressProvider,
                            onSeek = onSeek,
                            barHeight = 6.dp,
                            trackColor = trackColor,
                            fillColor = fg,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MonoTime(positionProvider, color = fgDim)
                            MonoTime(
                                { (durationMs - positionProvider()).coerceAtLeast(0L) },
                                color = fgFaint,
                                prefix = "-",
                            )
                        }

                        Spacer(modifier = Modifier.height(controlsMarginTop))
                        TriCol(
                            a = {
                                FlatBtn(
                                    onClick = onPrevious,
                                    enabled = queueReady,
                                    size = sideButtonSize,
                                ) {
                                    SkipBackGlyph(
                                        color = fg,
                                        modifier = Modifier.size(clampDp(42.dp, viewportW * 0.11f, 58.dp)),
                                    )
                                }
                            },
                            b = {
                                FlatBtn(
                                    onClick = onToggle,
                                    enabled = queueReady,
                                    size = centerButtonSize,
                                ) {
                                    val centerGlyphSize = clampDp(58.dp, viewportW * 0.15f, 78.dp)
                                    if (isLoading) {
                                        LoadingRing(color = fg, modifier = Modifier.size(centerGlyphSize * 0.68f))
                                    } else if (isPlaying) {
                                        PauseGlyph(color = fg, modifier = Modifier.size(centerGlyphSize))
                                    } else {
                                        PlayGlyph(color = fg, modifier = Modifier.size(centerGlyphSize))
                                    }
                                }
                            },
                            c = {
                                FlatBtn(
                                    onClick = onNext,
                                    enabled = queueReady,
                                    size = sideButtonSize,
                                ) {
                                    SkipForwardGlyph(
                                        color = fg,
                                        modifier = Modifier.size(clampDp(42.dp, viewportW * 0.11f, 58.dp)),
                                    )
                                }
                            },
                        )

                        Spacer(modifier = Modifier.height(removedVolumeGap))
                        TriCol(
                            a = {
                                NavIconBtn(onClick = onOpenLyrics, enabled = lyricsReady) {
                                    LyricsIcon(color = fg, modifier = Modifier.size(24.dp))
                                }
                            },
                            b = {
                                NavIconBtn(onClick = onOpenDistill) {
                                    ListIcon(color = fg, modifier = Modifier.size(24.dp))
                                }
                            },
                            c = {
                                NavIconBtn(onClick = onOpenSettings) {
                                    GearIcon(color = fg, modifier = Modifier.size(24.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleMusicPlayerBackdrop(coverUrl: String?) {
    val configuration = LocalConfiguration.current
    val screenW = configuration.screenWidthDp.dp
    val screenH = configuration.screenHeightDp.dp
    val clearCoverHeight = (screenW * 1.20f)
        .coerceAtLeast(screenW)
        .coerceAtMost(screenH * 0.64f)
    val edges = useCoverEdgeColors(coverUrl)
    val surfaceColor = appleMusicPureSurfaceColor(edges)
    val bridgeColor = appleMusicDissolveBridgeColor(edges, fallback = surfaceColor)
    val topColor = appleMusicPureTopColor(edges, fallback = bridgeColor)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to topColor,
                        0.30f to bridgeColor,
                        0.54f to lerp(bridgeColor, surfaceColor, 0.28f),
                        0.76f to lerp(bridgeColor, surfaceColor, 0.72f),
                        1.00f to surfaceColor,
                    ),
                ),
            ),
    ) {
        AppleMusicLowerGlassWash(
            coverUrl = coverUrl,
        )

        AppleMusicTopCover(
            coverUrl = coverUrl,
            height = clearCoverHeight,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.50f to Color.Transparent,
                            0.74f to surfaceColor.copy(alpha = 0.20f),
                            1.00f to surfaceColor.copy(alpha = 0.52f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun AppleMusicLowerGlassWash(
    coverUrl: String?,
) {
    if (coverUrl == null) return
    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        CrossfadeCoverImage(
            url = coverUrl,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.30f
                    scaleX = 1.78f
                    scaleY = 1.78f
                }
                .blur(64.dp),
            contentScale = ContentScale.Crop,
            durationMs = PipoMotion.CoverFadeMs,
            maxDecodeSizePx = 448,
        )
    }
}

@Composable
private fun AppleMusicTopCover(
    coverUrl: String?,
    height: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .reportCoverRect()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black,
                            0.48f to Color.Black,
                            0.64f to Color.Black.copy(alpha = 0.92f),
                            0.80f to Color.Black.copy(alpha = 0.54f),
                            0.94f to Color.Black.copy(alpha = 0.13f),
                            1.00f to Color.Transparent,
                        ),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        if (coverUrl != null) {
            CrossfadeCoverImage(
                url = coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.025f
                        scaleY = 1.025f
                    }
                    .background(Color(0xFF11151D)),
                contentScale = ContentScale.Crop,
                durationMs = PipoMotion.CoverFadeMs,
                maxDecodeSizePx = 960,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                PipoColors.Accent.copy(alpha = 0.18f),
                                PipoColors.Bg1,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_round),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.22f),
                )
            }
        }
    }
}

@Composable
private fun CompactCover(coverUrl: String?, isPlaying: Boolean, hidden: Boolean) {
    val hideAlpha by animateFloatAsState(
        targetValue = if (hidden) 0f else 1f,
        animationSpec = tween(140),
        label = "coverHide",
    )
    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.012f else 1f,
        animationSpec = tween(2800),
        label = "playScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .reportCoverRect()
            // React boxShadow: 0 24px 64px rgba(0,0,0,0.5)
            // Compose shadow elevation 不能直接给 offset/blur，elevation=24dp 是
            // 视觉上最接近的近似（自带 alpha 0.5 的黑色 spotShadow）。
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(PipoDimens.CompactCornerDp),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            )
            // 0 0 0 1px rgba(255,255,255,0.04) ring
            .border(
                width = 1.dp,
                color = Color(0x0AFFFFFF),
                shape = RoundedCornerShape(PipoDimens.CompactCornerDp),
            )
            .clip(RoundedCornerShape(PipoDimens.CompactCornerDp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0x1A9BE3C6),
                        Color(0x059BE3C6),
                    ),
                ),
            )
            .alpha(hideAlpha),
    ) {
        if (coverUrl != null) {
            CrossfadeCoverImage(
                url = coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = playScale
                        scaleY = playScale
                    },
                contentScale = ContentScale.Crop,
                durationMs = 720, // COVER_TRANSITION_MS
            )
        } else {
            // 网盘上传 / 没匹配到库的歌经常拿不到 NetEase 封面也没内嵌 artwork ——
            // 这里用 app logo 做兜底，比纯绿色渐变更易识别"这首歌没有封面"。
            // 不铺满，居中显示 ~40% 让蒙层 + 渐变背景还透得出来。
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_round),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize(0.42f)
                        .graphicsLayer {
                            alpha = 0.85f
                            scaleX = playScale
                            scaleY = playScale
                        },
                )
            }
        }
    }
}

/**
 * 自绘进度条 —— 4dp 实心圆角条，无 thumb，按下任意位置跳到对应进度。
 *   - 轨道：rgba(233,239,255,0.12)
 *   - 填充：rgba(245,247,255,0.92)
 *   - 跟 React `progressFill { transition: width 120ms linear }` 同款
 */
@Composable
internal fun PipoProgressBar(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Color(0x1FE9EFFF),
    fillColor: Color = Color(0xEBF5F7FF),
    barHeight: Dp = 4.dp,
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    // progress() 在此惰性读取 viewModel.positionMs —— 仅本进度条随进度 30Hz 重组,父级不受影响。
    val displayProgress = (draggingProgress ?: progress()).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = displayProgress,
        animationSpec = tween(durationMillis = if (draggingProgress == null) 120 else 0),
        label = "progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(onSeek) {
                detectTapGestures { offset ->
                    val target = progressFromOffset(offset.x, size.width)
                    draggingProgress = target
                    onSeek(target)
                    draggingProgress = null
                }
            }
            .pointerInput(onSeek) {
                detectDragGestures(
                    onDragStart = { offset ->
                        draggingProgress = progressFromOffset(offset.x, size.width)
                    },
                    onDragCancel = { draggingProgress = null },
                    onDragEnd = {
                        draggingProgress?.let(onSeek)
                        draggingProgress = null
                    },
                ) { change, _ ->
                    draggingProgress = progressFromOffset(change.position.x, size.width)
                    change.consume()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(50))
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animated)
                    .height(barHeight)
                    .clip(RoundedCornerShape(50))
                    .background(fillColor),
            )
        }
    }
}

private fun progressFromOffset(x: Float, width: Int): Float {
    if (width <= 0) return 0f
    return (x / width.toFloat()).coerceIn(0f, 1f)
}

@Composable
internal fun LoadingRing(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFF5F7FF),
) {
    CircularProgressIndicator(
        modifier = modifier,
        color = color,
        strokeWidth = 2.4.dp,
        trackColor = Color.Transparent,
    )
}

@Composable
private fun MonoTime(ms: () -> Long, color: Color, prefix: String = "") {
    val total = (ms() / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    Text(
        text = "$prefix${m}:${s.toString().padStart(2, '0')}",
        color = color,
        style = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        ),
    )
}

@Composable
private fun TriCol(
    modifier: Modifier = Modifier,
    a: @Composable () -> Unit,
    b: @Composable () -> Unit,
    c: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { a() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { b() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { c() }
    }
}

@Composable
private fun FlatBtn(
    onClick: () -> Unit,
    enabled: Boolean,
    size: Dp = PipoDimens.SkipButtonSize,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun NavIconBtn(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 0.82f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ---------- clamp 工具 ----------

/** CSS clamp(min, prefer, max) 在 dp 上的等价 */
private fun clampDp(min: Dp, prefer: Dp, max: Dp): Dp {
    return when {
        prefer < min -> min
        prefer > max -> max
        else -> prefer
    }
}

private fun clampSp(min: Int, preferDp: Float, max: Int): androidx.compose.ui.unit.TextUnit {
    val v = preferDp.coerceIn(min.toFloat(), max.toFloat())
    return v.sp
}

private fun immersiveTitleTop(screenWidth: Dp): Dp =
    (screenWidth - 18.dp).coerceAtLeast(52.dp)
