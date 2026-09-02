package app.yxi.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.yxi.agent.LabApprovals
import app.yxi.agent.LabRemote
import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 实验室 —— **你的 agent 产出的东西，按类型分栏的库**。
 *
 * 内容全从服务器 `~/.yxi/lab/` 读（agent 用 `yxi-lab add` 推）。App 里不再嵌任何写死的 demo。
 * 顶层是**栏目**（按类型：图像/矢量/动图/视频/网页/文字），**左滑出置顶/删除**，点开看里面的条目；
 * 每条标了 **由谁生成**、**更新时间（北京）**，能勾选审核。加新东西不用更新 App，点刷新就见。
 */
private val Pill = RoundedCornerShape(100.dp)

@Composable
private fun catName(key: String): String = when (key) {
    "image" -> t("图像"); "svg" -> t("矢量"); "gif" -> t("动图")
    "video" -> t("视频"); "html" -> t("网页"); else -> t("文字")
}

private class LabCat(val key: String, val items: List<LabRemote.Item>) {
    val latestAt get() = items.maxOfOrNull { it.at } ?: 0L
    val makers get() = items.mapNotNull { it.by.takeIf(String::isNotBlank) }.distinct()
}

/** unix 秒 → 北京时间 `MM-dd HH:mm`。0 或解析不了就空串。 */
private fun beijing(at: Long): String {
    if (at <= 0) return ""
    return runCatching {
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .format(java.time.Instant.ofEpochSecond(at).atZone(java.time.ZoneId.of("Asia/Shanghai")))
    }.getOrDefault("")
}

@Composable
/**
 * ⚠️ **实验室跟着服务器走**（用户定的，D26）：内容全在连着那台的 `~/.yxi/lab/`，
 * 置顶 / 采纳也按主机分开存 —— 两台服务器的实验室互不相干，App 里更不嵌任何实验室内容。
 */
fun LabScreen(ssh: SshSession? = null, host: app.yxi.ssh.Host? = null, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf<List<LabRemote.Item>?>(null) }
    var approved by remember { mutableStateOf<Set<String>>(emptySet()) }
    val hostId = host?.id ?: ""
    var pins by remember(hostId) { mutableStateOf(LabPins.get(ctx, hostId)) }
    var openCat by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(ssh, reload) {
        raw = null
        raw = LabRemote.load(ssh)
        approved = LabApprovals.load(ssh)
    }
    val list = raw

    fun delete(ids: List<String>) {
        raw = raw?.filter { it.id !in ids }               // 乐观移除
        scope.launch { LabRemote.delete(ssh, ids) }
    }
    fun toggleApprove(id: String) {
        val on = id !in approved
        approved = if (on) approved + id else approved - id
        scope.launch { LabApprovals.set(ssh, id, on) }
    }

    val cats = remember(list, pins) {
        (list ?: emptyList()).groupBy { it.catKey }
            .map { (k, v) -> LabCat(k, v.sortedByDescending { it.at }) }
            .sortedWith(compareByDescending<LabCat> { it.key in pins }.thenByDescending { it.latestAt })
    }

    // ── 栏目详情 ──
    val cat = openCat?.let { k -> cats.firstOrNull { it.key == k } }
    if (openCat != null) {
        CategoryDetail(
            title = catName(openCat!!),
            cat = cat, approved = approved,
            onBack = { openCat = null },
            onApprove = ::toggleApprove,
            onDelete = { id -> delete(listOf(id)) },
            ssh = ssh, modifier = modifier,
        )
        return
    }

    // ── 栏目列表 ──
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("实验室"), style = MaterialTheme.typography.headlineSmall)
                Text(
                    (host?.alias?.let { t("%s 这台的 · ").format(it) } ?: "") + t("agent 产出的东西 · 按类型分栏 · 左滑置顶/删除"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
                modifier = Modifier.clip(Pill).clickable { reload++ }) {
                Text(t("↻ 刷新"), Modifier.padding(14.dp, 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            cats.isEmpty() -> Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
                Text(
                    t("还是空的。\n\n让你的 agent 用 yxi-lab add <文件> 推东西进来（图像 / 网页 / 动图…），\n点上面「刷新」就按类型分栏出现 —— 不用更新 App。"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp, 6.dp, 14.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cats, key = { it.key }) { c ->
                    SwipeActions(
                        pinned = c.key in pins,
                        onPin = { pins = LabPins.toggle(ctx, hostId, c.key) },
                        onDelete = { delete(c.items.map { it.id }) },
                    ) {
                        CategoryRow(c, pinned = c.key in pins) { openCat = c.key }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(c: LabCat, pinned: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pinned) Text("📌", style = MaterialTheme.typography.labelMedium)
                    Text(catName(c.key), style = MaterialTheme.typography.titleLarge)
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = Pill) {
                        Text("${c.items.size}", Modifier.padding(9.dp, 2.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                val sub = buildString {
                    if (c.makers.isNotEmpty()) append(t("由 %s 生成").format(c.makers.joinToString("、")))
                    beijing(c.latestAt).takeIf { it.isNotEmpty() }?.let {
                        if (isNotEmpty()) append(" · "); append(t("%s 更新（北京）").format(it))
                    }
                }
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ── 左滑露出「置顶 / 删除」──────────────────────────────────────────
@Composable
private fun SwipeActions(
    pinned: Boolean, onPin: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit,
) {
    val d = LocalDensity.current
    val revealPx = with(d) { 168.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun close() = scope.launch { offset.animateTo(0f) }

    Box(Modifier.fillMaxWidth()) {
        // 背后的两个按钮（右侧）
        Row(
            Modifier.matchParentSize().clip(MaterialTheme.shapes.large),
            horizontalArrangement = Arrangement.End,
        ) {
            ActionCell(t(if (pinned) "取消置顶" else "置顶"), MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer) { close(); onPin() }
            ActionCell(t("删除"), MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer) { close(); onDelete() }
        }
        // 前面可拖的内容
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dx ->
                            scope.launch { offset.snapTo((offset.value + dx).coerceIn(-revealPx, 0f)) }
                        },
                        onDragEnd = {
                            scope.launch { offset.animateTo(if (offset.value < -revealPx / 2) -revealPx else 0f) }
                        },
                    )
                },
        ) { content() }
    }
}

@Composable
private fun ActionCell(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(color = bg, modifier = Modifier.fillMaxHeight().width(84.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── 栏目详情：这一类里的条目 ─────────────────────────────────────────
@Composable
private fun CategoryDetail(
    title: String, cat: LabCat?, approved: Set<String>,
    onBack: () -> Unit, onApprove: (String) -> Unit, onDelete: (String) -> Unit,
    ssh: SshSession?, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                modifier = Modifier.clip(Pill).clickable(onClick = onBack)) {
                Text("←", Modifier.padding(15.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(t("%d 条").format(cat?.items?.size ?: 0), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline)
        }
        val itemsList = cat?.items ?: emptyList()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(itemsList, key = { it.id }) { item ->
                SwipeActions(pinned = false, onPin = { }, onDelete = { onDelete(item.id) }, content = {
                    LabItemCard(item, ssh, approved = item.id in approved, onApprove = { onApprove(item.id) })
                })
            }
        }
    }
}

/**
 * **一张实验室卡片的模板** —— 所有类型长一样：标题 + 类型 · 预览框（按 aspect 定高，不裁不空）·
 * 说明 · 谁/何时 · 一排动作（采纳 / 全屏 / 存到本地）。
 *
 * ⚠️ 规矩（用户定的，D27）：内容从服务器来，App 只按这一套模板画；新类型/新内容不改 App，
 * 改的是 `yxi-lab`（`yxi-lab spec` 是契约）。
 */
@Composable
private fun LabItemCard(item: LabRemote.Item, ssh: SshSession?, approved: Boolean, onApprove: () -> Unit) {
    var full by remember(item.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    catName(item.catKey),
                    Modifier.clip(Pill).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(9.dp, 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 预览框：网页/视频按声明的比例定高（WebView 尺寸必须一开始就定，不然首帧 0×0 画布空着）；
            // 图/GIF 按原图等比缩放、最高 360dp，**不裁**。
            if (item.type != "note") {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val r = item.ratio
                    val box = if (r != null) Modifier.fillMaxWidth().height((maxWidth / r).coerceIn(140.dp, 480.dp))
                              else Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    Box(box.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)) {
                        LabItemPreview(item, ssh, full = false)
                    }
                }
            }
            if (item.desc.isNotBlank())
                Text(item.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val meta = buildString {
                if (item.by.isNotBlank()) append(t("由 %s 生成").format(item.by))
                beijing(item.at).takeIf { it.isNotEmpty() }?.let {
                    if (isNotEmpty()) append(" · "); append(t("%s（北京）").format(it))
                }
            }
            if (meta.isNotBlank())
                Text(meta, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline)
            // 动作：采纳 · 全屏 · 存到本地
            val ctx = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = Pill, modifier = Modifier.clip(Pill).clickable(onClick = onApprove),
                ) {
                    Text(if (approved) t("✓ 已采纳") else t("采纳"),
                        Modifier.padding(16.dp, 9.dp), style = MaterialTheme.typography.labelLarge,
                        color = if (approved) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.type != "note") Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = Pill,
                    modifier = Modifier.clip(Pill).clickable { full = true },
                ) {
                    Text(t("全屏"), Modifier.padding(16.dp, 9.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
            }
            if (item.file.isNotBlank()) SubmitButton(
                label = when (item.type) {
                    "image", "svg" -> t("保存原图"); "gif" -> t("保存 GIF"); "video" -> t("保存视频")
                    else -> t("下载到本地")
                },
                height = 46.dp, modifier = Modifier.fillMaxWidth(), successLabel = t("已保存"),
            ) { progress ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val name = item.file.substringAfterLast('/').ifBlank { "yxi-${item.id}" }
                        val mime = MediaSaver.mimeOf(item.file, item.type)
                        val tmp = java.io.File(ctx.cacheDir, "labdl/$name")
                        val got = LabRemote.download(ssh, item.file, tmp) { progress(it) }
                        if (got <= 0L) throw RuntimeException(t("下载失败"))
                        MediaSaver.save(ctx, name, mime, tmp)
                        runCatching { tmp.delete() }
                        if (mime.startsWith("image/") || mime.startsWith("video/")) t("已存到相册") else t("已存到下载目录")
                    }
                }
            }
        }
    }
    // 全屏：整屏铺满重新加载一遍（网页动画在小框里看不清细节）
    if (full) androidx.compose.ui.window.Dialog(
        onDismissRequest = { full = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            LabItemPreview(item, ssh, full = true)
            Text(
                "✕", Modifier.align(Alignment.TopEnd).padding(18.dp).clip(Pill)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { full = false }.padding(12.dp, 6.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 按 type 渲染预览。html→WebView(JS 开)；gif→ImageDecoder；image→Image；note→文字。 */
@Composable
private fun LabItemPreview(item: LabRemote.Item, ssh: SshSession?, full: Boolean = false) {
    val fill = if (full) Modifier.fillMaxSize() else Modifier.fillMaxSize()
    when (item.type) {
        "html" -> {
            // ⚠️ 键上 ssh：连接换过一条（手机上重连是常态）之后，老的 produceState 还拿着死连接的空结果不动，
            // 卡片就永远是白的。空串 = 读失败，**说出来**，别灌一个空页面进 WebView 装作在加载。
            val html by produceState<String?>(null, item.id, ssh) { value = LabRemote.text(ssh, item.file).ifBlank { null } ?: "" }
            var jsError by remember(item.id) { mutableStateOf<String?>(null) }
            when {
                html == null -> PreviewLoading()
                html!!.isEmpty() -> Box(fill, contentAlignment = Alignment.Center) {
                    Text(t("读不到这条内容（连接断了？）—— 点上面的刷新"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(16.dp))
                }
                else -> Box(fill) {
                    val h = html!!
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { c -> android.webkit.WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false; settings.allowContentAccess = false
                            settings.useWideViewPort = false; settings.loadWithOverviewMode = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            setBackgroundColor(android.graphics.Color.WHITE)
                            // 页面里的 JS 报错直接显示在卡片上 —— 手机上没法开 DevTools，这是唯一的眼睛
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                                        jsError = "${m.message()} (line ${m.lineNumber()})"
                                    return true
                                }
                            }
                            // ⚠️ **等布局定了再加载。** WebView 刚建出来是 0×0，页面这时候读到的
                            // innerWidth/innerHeight 是 0，画布就 0×0；等到有尺寸再 load，首帧就对。
                            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this, null)
                            post { loadDataWithBaseURL(null, h, "text/html", "utf-8", null) }
                        } },
                        update = { },
                        modifier = Modifier.fillMaxSize(),
                    )
                    jsError?.let { e ->
                        Text(t("页面报错：%s").format(e), Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer).padding(8.dp, 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 3)
                    }
                }
            }
        }
        "gif" -> {
            val b by produceState<ByteArray?>(null, item.id, ssh) { value = LabRemote.bytes(ssh, item.file) }
            b?.let { bytes ->
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c -> android.widget.ImageView(c).apply {
                        adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            val d = runCatching { android.graphics.ImageDecoder.decodeDrawable(
                                android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) }.getOrNull()
                            setImageDrawable(d); (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                        } else setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    } },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                )
            } ?: PreviewLoading()
        }
        "image", "svg" -> {
            val b by produceState<ByteArray?>(null, item.id, ssh) { value = LabRemote.bytes(ssh, item.file) }
            val bmp = remember(b) { b?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
            bmp?.let {
                // ⚠️ Fit 不是 FillWidth：竖图按宽铺开会比框高，被裁掉一截（用户：「有些展示不全」）
                Image(it.asImageBitmap(), null, fill.clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit)
            } ?: PreviewLoading()
        }
        else -> Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(item.desc.ifBlank { item.title }, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PreviewLoading() {
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
    }
}

/** 置顶的栏目（按类型 key）。本地存 —— 是每台设备自己的偏好，跟别人无关。 */
/** 栏目置顶。⚠️ **按主机分开存**：两台服务器的实验室是两个实验室（D26）。 */
private object LabPins {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    private fun k(hostId: String) = "lab_cat_pins:" + hostId
    fun get(ctx: Context, hostId: String): Set<String> = p(ctx).getStringSet(k(hostId), emptySet())!!.toSet()
    fun toggle(ctx: Context, hostId: String, key: String): Set<String> {
        val cur = get(ctx, hostId).toMutableSet()
        if (!cur.add(key)) cur.remove(key)
        p(ctx).edit().putStringSet(k(hostId), cur).apply()
        return cur
    }
}
