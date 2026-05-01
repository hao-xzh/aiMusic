package app.pipo.nativeapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.playback.PlayerViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 子页面用的返回回调 —— PipoNativeApp 在 push 进入前提供，返回到 Player root。 */
val LocalOnBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 子页间互跳：DistillScreen 蒸馏完成跳到 TasteScreen 等场景用 */
data class PipoNav(
    val openTaste: () -> Unit,
    val openSettings: () -> Unit,
    val openDistill: () -> Unit,
)
val LocalNav = staticCompositionLocalOf<PipoNav?> { null }

@Composable
fun TasteScreen() {
    val profile by app.pipo.nativeapp.data.PipoGraph.tasteProfileStore.flow.collectAsState()
    val nav = LocalNav.current
    ScreenScaffold(title = "TASTE") {
        val p = profile
        if (p == null) {
            EmptyState(
                title = "还没蒸馏",
                subtitle = "去歌单页点右上 \"蒸馏\" 按钮，挑几张歌单蒸馏一份你的口味画像。",
            )
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .clip(CircleShape)
                    .background(PipoColors.Mint.copy(alpha = 0.18f))
                    .clickable { nav?.openDistill?.invoke() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("去歌单页", color = PipoColors.Mint, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            }
            return@ScreenScaffold
        }

        // ---- Hero：summary + taglines ----
        Text(
            text = p.summary,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        p.taglines.forEach { line ->
            Text(
                text = line,
                color = PipoColors.TextMuted,
                style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ---- 统计三连 ----
        StatGrid(
            items = listOf(
                "歌单" to p.sourcePlaylistCount.toString(),
                "总曲目" to p.totalTrackCount.toString(),
                "AI 蒸馏" to "${p.sampledTrackCount} 首",
                "更新于" to dateOnly(p.derivedAt),
            ),
        )
        Spacer(modifier = Modifier.height(28.dp))

        // ---- 主流派（带权重条） ----
        SectionTitle("主流派")
        p.genres.forEach { g ->
            GenreRow(g)
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ---- 年代倾向（横向 segmented） ----
        if (p.eras.isNotEmpty()) {
            SectionTitle("年代倾向")
            EraBars(p.eras)
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ---- 情绪关键词（chips） ----
        if (p.moods.isNotEmpty()) {
            SectionTitle("情绪关键词")
            ChipFlow(p.moods, accent = PipoColors.Mint)
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ---- Top 艺人（带 affinity 条） ----
        if (p.topArtists.isNotEmpty()) {
            SectionTitle("Top 艺人")
            p.topArtists.forEach { a ->
                ArtistRow(a)
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ---- 文化坐标 ----
        if (p.culturalContext.isNotEmpty()) {
            SectionTitle("文化坐标")
            ChipFlow(p.culturalContext, accent = PipoColors.Blue)
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun DistillScreen(repository: PipoRepository = PipoGraph.repository) {
    val playlists by repository.playlists.collectAsState(initial = emptyList())
    val account by repository.account.collectAsState(initial = null)
    LaunchedEffect(Unit) {
        repository.refreshAccount()
        repository.refreshPlaylists()
    }
    if (account == null) {
        ScreenScaffold(title = "我的歌单") {
            EmptyState(
                title = "还没登录",
                subtitle = "去设置页扫码或用手机号登录网易云。",
            )
        }
        return
    }
    if (playlists.isEmpty()) {
        ScreenScaffold(title = "我的歌单") {
            EmptyState(
                title = "你还没有歌单",
                subtitle = "等账号同步完，歌单就会出现在这里。",
            )
        }
        return
    }
    DistillLibrary(playlists = playlists, repository = repository)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DistillLibrary(
    playlists: List<app.pipo.nativeapp.data.PipoPlaylist>,
    repository: PipoRepository,
) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = 0,
        pageCount = { playlists.size },
    )
    val focused = playlists.getOrNull(pagerState.currentPage)
    var tracks by remember { mutableStateOf<List<app.pipo.nativeapp.data.NativeTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(focused?.id) {
        val id = focused?.id ?: return@LaunchedEffect
        loading = true
        tracks = runCatching { repository.tracksForPlaylist(id) }.getOrDefault(emptyList())
        loading = false
    }

    // ---- 蒸馏状态 + 进度 + 多选模式 ----
    // 蒸馏跑在 app-level coordinator（DistillCoordinator），跟 Composable 进出无关。
    // 这里只用它的 StateFlow 来呈现 UI。即使用户切走，蒸馏仍在后台跑。
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    val coordinator = app.pipo.nativeapp.data.PipoGraph.distillCoordinator
    val distillRunning by coordinator.running.collectAsState()
    var confirmOpen by remember { mutableStateOf(false) }
    // mode = "library" 是 cover-flow 视图；"select" 是多选 / 准备蒸馏
    var mode by remember { mutableStateOf("library") }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }
    val selectedPlaylists = remember(selectedIds.size, playlists) {
        playlists.filter { selectedIds[it.id] == true }
    }
    val selectedTrackEstimate = selectedPlaylists.sumOf { it.trackCount }

    if (confirmOpen && selectedPlaylists.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("蒸馏 ${selectedPlaylists.size} 张歌单？", color = PipoColors.Ink) },
            text = {
                Text(
                    "总曲目约 $selectedTrackEstimate 首，sample ~200 首喂 AI 写口味画像。约 30 秒。会覆盖之前的画像。后台跑，期间可以正常用 app。",
                    color = PipoColors.TextDim,
                    style = TextStyle(fontSize = 13.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    val playlistsToDistill = selectedPlaylists.toList()
                    coordinator.start(
                        playlists = playlistsToDistill,
                        onComplete = {
                            // 蒸馏完后让本地库 cache 也刷新，让 PetAgent 召回拿到最新的曲库
                            app.pipo.nativeapp.data.PipoGraph.library.invalidate()
                        },
                    )
                    mode = "library"
                    selectedIds.clear()
                    // 不再 navigate 到 TasteScreen —— 后台跑期间用户该停在哪由 TA 自己决定
                }) { Text("开始", color = PipoColors.Mint) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("取消", color = PipoColors.TextDim) }
            },
            containerColor = PipoColors.Bg1,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0),
    ) {
        // 全屏背景：当前焦点歌单封面 blur
        Box(modifier = Modifier.fillMaxSize()) {
            AdaptiveDotField(
                coverUrl = focused?.coverUrl,
                isPlaying = false,
                showDots = false,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 6.dp, bottom = 60.dp),
            ) {
                // header —— title 紧贴返回箭头，跟 ScreenScaffold（设置页）对齐
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val onBack = LocalOnBack.current
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        if (onBack != null) {
                            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text("←", color = PipoColors.Ink, style = TextStyle(fontSize = 22.sp))
                            }
                        }
                    }
                    Text(
                        "我的歌单",
                        color = PipoColors.Ink,
                        style = TextStyle(fontSize = 14.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 右侧两个 icon chip：蒸馏 / 画像
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconChip(
                            tint = if (mode == "select") PipoColors.Mint else PipoColors.Ink,
                            onClick = {
                                if (mode == "select" && selectedPlaylists.isNotEmpty()) {
                                    confirmOpen = true
                                } else {
                                    mode = if (mode == "select") "library" else "select"
                                    if (mode == "library") selectedIds.clear()
                                }
                            },
                        ) { SparkIcon(modifier = Modifier.size(20.dp)) }

                        IconChip(
                            tint = PipoColors.Ink,
                            onClick = { nav?.openTaste?.invoke() },
                        ) { ProfileIcon(modifier = Modifier.size(20.dp)) }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (mode == "select") {
                    // 多选模式：歌单 + checkbox 列表 + 底部"开始 N 张"操作
                    Row(
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "选择歌单蒸馏",
                            color = PipoColors.Ink,
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedPlaylists.isNotEmpty()) {
                            TextButton(onClick = { selectedIds.clear() }) {
                                Text("全清", color = PipoColors.TextDim, style = TextStyle(fontSize = 12.sp))
                            }
                        } else {
                            TextButton(onClick = { playlists.forEach { selectedIds[it.id] = true } }) {
                                Text("全选", color = PipoColors.TextDim, style = TextStyle(fontSize = 12.sp))
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                    ) {
                        items(items = playlists, key = { it.id }) { p ->
                            val checked = selectedIds[p.id] == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedIds[p.id] = !checked }
                                    .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // cover thumbnail
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0x16FFFFFF)),
                                ) {
                                    if (!p.coverUrl.isNullOrBlank()) {
                                        coil.compose.AsyncImage(
                                            model = p.coverUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        p.name,
                                        color = PipoColors.Ink,
                                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${p.trackCount} 首",
                                        color = PipoColors.TextDim,
                                        style = TextStyle(fontSize = 11.sp),
                                    )
                                }
                                androidx.compose.material3.Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selectedIds[p.id] = it },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                                        checkedColor = PipoColors.Mint,
                                        uncheckedColor = Color(0x55FFFFFF),
                                        checkmarkColor = PipoColors.Bg0,
                                    ),
                                )
                            }
                        }
                    }
                    return@Column  // 跳过下方 cover-flow / 歌单标题 / 曲目列表
                }

                // cover-flow pager
                //   - 焦点 cover 加大到 280dp（之前 220dp）
                //   - contentPadding 不对称：左 24dp 让焦点 cover 左边沿恰好对齐下方"歌单标题"的左边沿
                //     右 80dp 让"下一张"以小片露出来作为下一页提示（Apple Music 风）
                //   - pageSpacing 缩小到 8dp，左右两侧的封面更紧凑
                val playerVm: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    pageSize = androidx.compose.foundation.pager.PageSize.Fixed(280.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 80.dp),
                    pageSpacing = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(310.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Black,
                                        0.94f to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) { index ->
                    val playlist = playlists[index]
                    val pageOffset = (
                        (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                    ).coerceIn(-1f, 1f)
                    val absOffset = kotlin.math.abs(pageOffset)
                    val scale = 1f - absOffset * 0.14f
                    val alpha = 1f - absOffset * 0.4f
                    val rotY = pageOffset * 24f
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                                rotationY = rotY
                                cameraDistance = 12f * density
                            }
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x16FFFFFF)),
                    ) {
                        if (!playlist.coverUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = playlist.coverUrl,
                                contentDescription = playlist.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 焦点歌单标题 —— 左边沿严格对齐 cover 左边沿 24dp
                if (focused != null) {
                    Text(
                        text = focused.name,
                        color = PipoColors.Ink,
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${focused.trackCount} 首",
                        color = PipoColors.TextDim,
                        style = TextStyle(fontSize = 12.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 4.dp),
                    )
                }

                // 曲目列表 —— LazyColumn + weight(1f)，铺到屏幕底部不截断、不限 80 首
                val onBack = LocalOnBack.current
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    when {
                        loading -> item {
                            Text(
                                "加载中…",
                                color = PipoColors.TextDim,
                                style = TextStyle(fontSize = 13.sp),
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        }
                        tracks.isEmpty() -> item {
                            Text(
                                "这张歌单是空的",
                                color = PipoColors.TextDim,
                                style = TextStyle(fontSize = 13.sp),
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        }
                        else -> {
                            items(
                                items = tracks,
                                key = { it.id },
                            ) { t ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            t.title,
                                            color = PipoColors.Ink,
                                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            t.artist,
                                            color = PipoColors.TextDim,
                                            style = TextStyle(fontSize = 12.sp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                playerVm.playTrack(t, tracks)
                                                onBack?.invoke()
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PlayGlyph(
                                            color = PipoColors.Ink,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 蒸馏全屏遮罩已删除 —— 蒸馏在 DistillCoordinator 后台跑，
            // 进度由 shell 层（PipoNativeApp）统一画一条小浮条
        }
    }
}

private fun stageLabel(p: app.pipo.nativeapp.data.DistillProgress): String = when (p) {
    is app.pipo.nativeapp.data.DistillProgress.LoadingTracks ->
        "拉曲目  ${p.done} / ${p.total}"
    app.pipo.nativeapp.data.DistillProgress.Sampling -> "分层采样中…"
    app.pipo.nativeapp.data.DistillProgress.CallingAi -> "AI 蒸馏中（约 30 秒）…"
    is app.pipo.nativeapp.data.DistillProgress.TaggingTracks ->
        "单曲打标  ${p.done} / ${p.total}"
    is app.pipo.nativeapp.data.DistillProgress.EmbeddingTracks ->
        "向量索引  ${p.done} / ${p.total}"
    app.pipo.nativeapp.data.DistillProgress.Done -> "完成"
}

@Composable
private fun IconChip(
    tint: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides tint,
        ) { content() }
    }
}

@Composable
fun SettingsScreen(repository: PipoRepository = PipoGraph.repository) {
    val account by repository.account.collectAsState(initial = null)
    val cacheStats by repository.audioCacheStats.collectAsState(
        initial = app.pipo.nativeapp.data.AudioCacheStats(0, 0, 0),
    )
    val aiConfig by repository.aiConfig.collectAsState(
        initial = app.pipo.nativeapp.data.AiConfigView(activeProvider = "", providers = emptyList()),
    )
    val settings by repository.settings.collectAsState(initial = NativeSettings())
    val scope = rememberCoroutineScope()
    var loginStatus by remember { mutableStateOf<String?>(null) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var apiKeyDraft by remember { mutableStateOf("") }
    var aiReply by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repository.refreshAccount()
        repository.refreshAudioCacheStats()
        repository.refreshAiConfig()
    }
    ScreenScaffold(title = "SETTINGS") {
        Text(
            "Pipo 把你的账号状态、播放规则、AI 口吻都攒在本地。",
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        SettingsGroup("音乐来源") {
            LabelRow(
                "网易云登录",
                account?.nickname ?: "用 QR 或手机号验证码登录",
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("手机号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = captcha,
                onValueChange = { captcha = it },
                label = { Text("验证码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        val start = repository.startQrLogin()
                        qrContent = start.qrContent
                        loginStatus = "等待扫码"
                        repeat(30) {
                            val status = repository.checkQrLogin(start.key)
                            loginStatus = status.nickname ?: status.message ?: "等待扫码"
                            if (status.code == 803) {
                                qrContent = null
                                repository.refreshAccount()
                                return@launch
                            }
                            if (status.code == 800 || status.code < 0) return@launch
                            delay(2_000)
                        }
                    }
                }) { Text("扫码", color = PipoColors.Mint) }
                TextButton(onClick = {
                    scope.launch {
                        val sent = repository.sendPhoneCaptcha(phone = phone)
                        loginStatus = sent.message ?: "验证码已发"
                    }
                }) { Text("获取验证码", color = PipoColors.Blue) }
                TextButton(onClick = {
                    scope.launch {
                        val status = repository.loginWithPhone(phone = phone, captcha = captcha)
                        loginStatus = status.nickname ?: status.message ?: "登录已提交"
                        repository.refreshAccount()
                    }
                }) { Text("登录", color = PipoColors.Gold) }
            }
            qrContent?.let { content ->
                QrCode(
                    content = content,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(180.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            loginStatus?.let { LabelRow("登录状态", it) }
        }

        SettingsGroup("音频缓存") {
            LabelRow(
                "歌曲原始字节缓存",
                "${cacheStats.totalMb} MB / ${cacheStats.maxMb} MB · ${cacheStats.count} 首",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { scope.launch { repository.clearAudioCache() } }) {
                    Text("清空缓存", color = PipoColors.Blue)
                }
                TextButton(onClick = { scope.launch { repository.setCacheMaxMb(4096) } }) {
                    Text("上限 4 GB", color = PipoColors.Gold)
                }
            }
        }

        SettingsGroup("AI key") {
            val activeProvider = aiConfig.providers.firstOrNull { it.id == aiConfig.activeProvider }
            val anyHasKey = aiConfig.providers.any { it.hasKey }
            LabelRow(
                "服务商",
                activeProvider?.let { "${it.label} · ${it.model}" } ?: "DeepSeek / OpenAI / MiMo",
            )
            if (!anyHasKey) {
                LabelRow(
                    "未配置",
                    "填入任一 provider 的 API key 才会有 AI 招呼 / 单曲点评 / Discovery",
                )
            }
            ToggleRow("DJ 旁白", settings.aiNarration) {
                scope.launch { repository.updateSettings(settings.copy(aiNarration = it)) }
            }
            aiConfig.providers.forEach { provider ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        LabelRow(
                            provider.label,
                            if (provider.hasKey) "key ${provider.keyPreview ?: "已存"}" else "未填 key",
                        )
                    }
                    TextButton(onClick = { scope.launch { repository.setAiProvider(provider.id) } }) {
                        Text(
                            if (provider.id == aiConfig.activeProvider) "当前" else "切换",
                            color = PipoColors.Mint,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = { apiKeyDraft = it },
                label = { Text("API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    scope.launch { repository.aiSetApiKey(aiConfig.activeProvider, apiKeyDraft) }
                }) { Text("保存 key", color = PipoColors.Blue) }
                TextButton(onClick = {
                    scope.launch { aiReply = repository.aiPing() }
                }) { Text("Ping", color = PipoColors.Gold) }
            }
            aiReply?.let { LabelRow("AI 回复", it) }
        }

        SettingsGroup("播放规则") {
            ToggleRow("工作时段自动播放", settings.workdayAutoplay) {
                scope.launch { repository.updateSettings(settings.copy(workdayAutoplay = it)) }
            }
            ToggleRow("会议时暂停", settings.pauseDuringMeetings) {
                scope.launch { repository.updateSettings(settings.copy(pauseDuringMeetings = it)) }
            }
            ToggleRow("午休换放松歌单", settings.lunchRelaxMode) {
                scope.launch { repository.updateSettings(settings.copy(lunchRelaxMode = it)) }
            }
        }

        SettingsGroup("外观") {
            ToggleRow("隐藏点阵叠加", settings.hideDotPattern) {
                scope.launch { repository.updateSettings(settings.copy(hideDotPattern = it)) }
            }
        }

        SettingsGroup("关于你") {
            OutlinedTextField(
                value = settings.userFacts,
                onValueChange = { value ->
                    val facts = value.take(400)
                    scope.launch { repository.updateSettings(settings.copy(userFacts = facts)) }
                    // 同步到 PetMemory —— 让 AI digest 能拿到，跟 React 端的字段同源
                    runCatching { app.pipo.nativeapp.data.PipoGraph.petMemory.setUserFacts(facts) }
                },
                label = { Text("工作时间 / 作息 / 习惯 / 喜好") },
                minLines = 4,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            LabelRow("已写", "${settings.userFacts.length} / 400")
        }
    }
}

@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { buildQrBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR login",
        modifier = modifier.background(Color.White),
        contentScale = ContentScale.FillBounds,
    )
}

private fun buildQrBitmap(content: String, size: Int = 512): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

/**
 * 子页面通用 scaffold —— 镜像 src/app/settings/page.tsx 的 36px 1fr 36px header 结构。
 *   - 左 36dp BackButton（由 LocalOnBack 提供）
 *   - 中 1fr 标题（textAlign center）
 *   - 右 36dp 占位（让标题中点真正落在屏幕中线）
 */
@Composable
private fun ScreenScaffold(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    // header 钉在顶部（不滚动），下面 content 单独 scroll —— 跟歌单页一致
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0)
            .statusBarsPadding(),
    ) {
        // 36 / 1fr / 36 网格 header（固定在顶部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onBack = LocalOnBack.current
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (onBack != null) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("←", color = PipoColors.Ink, style = TextStyle(fontSize = 22.sp))
                    }
                }
            }
            Text(
                text = title,
                color = PipoColors.Ink,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.size(36.dp))
        }

        // 内容滚动区
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 60.dp),
            content = content,
        )
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = PipoColors.TextDim,
            style = TextStyle(fontSize = 13.sp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = PipoColors.TextDim,
        style = TextStyle(fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun GenreRow(g: app.pipo.nativeapp.data.GenreTag) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(g.tag, color = PipoColors.Ink, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text("${(g.weight * 100).toInt()}%", color = PipoColors.TextDim, style = TextStyle(fontSize = 11.sp))
        }
        LinearProgressIndicator(
            progress = { g.weight.coerceIn(0f, 1f) },
            color = PipoColors.Mint,
            trackColor = Color(0x18FFFFFF),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(4.dp)
                .clip(CircleShape),
        )
        if (g.examples.isNotEmpty()) {
            Text(
                g.examples.joinToString(" · "),
                color = PipoColors.TextDim,
                style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EraBars(eras: List<app.pipo.nativeapp.data.EraSlice>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        eras.forEach { e ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((40 * e.weight.coerceIn(0f, 1f) + 4).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PipoColors.Mint.copy(alpha = 0.30f + 0.6f * e.weight)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    e.label,
                    color = PipoColors.TextDim,
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(items: List<String>, accent: Color) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { s ->
            Text(
                text = s,
                color = accent,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ArtistRow(a: app.pipo.nativeapp.data.ArtistAffinity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = a.name,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(Color(0x18FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(a.affinity.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(PipoColors.Mint),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(a.affinity * 100).toInt()}",
            color = PipoColors.TextDim,
            style = TextStyle(fontSize = 11.sp),
        )
    }
}

private fun dateOnly(epochSec: Long): String {
    if (epochSec <= 0) return "—"
    val date = java.time.Instant.ofEpochSecond(epochSec)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
    return "${date.monthValue}/${date.dayOfMonth}"
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x12FFFFFF))
                            .padding(16.dp),
                    ) {
                        Text(item.second, color = PipoColors.Ink, style = MaterialTheme.typography.headlineSmall)
                        Text(item.first, color = PipoColors.TextDim, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = label,
        color = PipoColors.Ink,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )
    Column(content = content)
}

@Composable
private fun LabelRow(label: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, color = PipoColors.Ink, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                detail,
                color = PipoColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = PipoColors.Ink, style = MaterialTheme.typography.titleSmall)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PipoColors.Bg0,
                checkedTrackColor = PipoColors.Mint,
                uncheckedThumbColor = PipoColors.TextDim,
                uncheckedTrackColor = Color(0x22FFFFFF),
            ),
        )
    }
}
