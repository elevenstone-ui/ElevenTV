package com.eleventv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eleventv.tv.R
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.MediaRoot
import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.model.StorageProtocol
import com.eleventv.tv.data.smb.SmbEntry
import com.eleventv.tv.ui.components.BrandLogo
import com.eleventv.tv.ui.components.ChoiceRow
import com.eleventv.tv.ui.components.ProtocolPicker
import com.eleventv.tv.ui.components.TvButton
import com.eleventv.tv.ui.components.TvButtonVariant
import com.eleventv.tv.ui.components.TvText
import com.eleventv.tv.ui.components.TvTextField
import com.eleventv.tv.ui.components.VSpace
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T
import com.eleventv.tv.ui.design.focusRing
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 添加 NAS（SMB / WebDAV）引导。
 *
 * 只在「一个服务器都没有」时出现。两步：
 * 1. 填连接参数 → 测试连接（必须成功才能继续）
 * 2. 浏览共享目录，选一个起始路径（也可以直接用共享根）
 *
 * 测试通过才放行是刻意的：参数错了直接存下来，用户会在首页看到一片空白，
 * 却不知道是地址错了还是共享名错了。
 */
@Composable
fun OnboardingScreen(
    onTest: suspend (SmbServer) -> Result<SmbServer>,
    onSave: (SmbServer) -> Unit,
    onBrowse: suspend (SmbServer, String) -> Result<List<SmbEntry>>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // 同一个 id 反复保存只会覆盖自己，不会在设置页里攒出一堆重复服务器
    val serverId = remember { "srv-" + UUID.randomUUID().toString().take(8) }

    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(StorageProtocol.SMB) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(StorageProtocol.SMB.defaultPort.toString()) }
    var share by remember { mutableStateOf("") }
    var basePath by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    // 媒体库根目录：可多选；一个都不选 = 整个共享
    val selected = remember { mutableStateOf(setOf<String>()) }

    // 目录 → 类型映射（null = 自动推断），Movie → 电影、Tv → 剧集
    val kindOf = remember { mutableStateOf<Map<String, MediaKind?>>(emptyMap()) }

    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 选目录状态
    var browsePath by remember { mutableStateOf("") }
    var browseItems by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var browsing by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }

    fun buildServer() = SmbServer(
        id = serverId,
        name = name.trim().ifBlank { host.trim() },
        host = host.trim(),
        port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: protocol.defaultPort,
        // 切到 WebDAV 也不清空：share 同时是文件 id 的一段，清掉等于整库换 id
        share = share.trim().trim('/'),
        username = user.trim(),
        password = pass,
        rootPath = "",
        protocol = protocol,
        basePath = if (protocol == StorageProtocol.WEBDAV) basePath.trim().trim('/') else "",
        mediaRoots = selected.value.sorted().map { MediaRoot(it, kindOf.value[it]) },
    )

    val valid = host.isNotBlank() &&
        (protocol == StorageProtocol.WEBDAV || share.isNotBlank())

    fun loadDir(path: String) {
        browsing = true
        browseError = null
        scope.launch {
            val result = onBrowse(buildServer(), path)
            browsing = false
            result.onSuccess {
                browsePath = path
                browseItems = it.filter { entry -> entry.isDirectory }
            }.onFailure {
                browseError = it.message ?: "无法读取目录"
            }
        }
    }

    fun runTest() {
        testing = true
        error = null
        scope.launch {
            val result = onTest(buildServer())
            testing = false
            result.onSuccess { ok ->
                // 测试可能把路径大小写改成了服务器上的实际写法，表单要跟着变：
                // 不跟就会「测试通过、保存的却是原来那份错的」
                if (ok.basePath != basePath) basePath = ok.basePath
                tested = true
                step = 1
                loadDir("")
            }.onFailure {
                tested = false
                error = it.message ?: "连接失败"
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(C.Ink0)
            .padding(horizontal = D.ScreenPaddingX, vertical = D.ScreenPaddingY),
        horizontalArrangement = Arrangement.spacedBy(D.X3l),
    ) {
        IntroPanel(modifier = Modifier.width(360.dp).fillMaxHeight())

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            StepDots(step)

            VSpace(D.X2l)

            if (step == 0) {
                ConnectionForm(
                    name = name,
                    onName = { name = it },
                    protocol = protocol,
                    onProtocol = { picked ->
                        if (picked != protocol) {
                            if (port.toIntOrNull() == protocol.defaultPort) port = picked.defaultPort.toString()
                            protocol = picked
                            tested = false
                            error = null
                        }
                    },
                    host = host,
                    onHost = { host = it; tested = false; error = null },
                    port = port,
                    onPort = { port = it; tested = false },
                    share = share,
                    onShare = { share = it; tested = false; error = null },
                    basePath = basePath,
                    onBasePath = { basePath = it; tested = false; error = null },
                    user = user,
                    onUser = { user = it },
                    pass = pass,
                    onPass = { pass = it },
                    testing = testing,
                    valid = valid,
                    error = error,
                    onSubmit = { if (valid && !testing) runTest() },
                )
            } else if (step == 1) {
                FolderPicker(
                    path = browsePath,
                    entries = browseItems,
                    selected = selected.value,
                    loading = browsing,
                    error = browseError,
                    onOpen = { loadDir(it) },
                    onToggle = { path ->
                        selected.value = if (path in selected.value) {
                            selected.value - path
                        } else {
                            selected.value + path
                        }
                    },
                    onUp = {
                        val parent = browsePath.substringBeforeLast('/', "")
                        loadDir(parent)
                    },
                    // 一个都没选 = 收录整个共享，类型无从映射，直接保存
                    onPick = {
                        if (selected.value.isEmpty()) onSave(buildServer()) else step = 2
                    },
                    onBack = { step = 0 },
                    onRetry = { loadDir(browsePath) },
                )
            } else {
                KindMapper(
                    paths = selected.value.sorted(),
                    kinds = kindOf.value,
                    onChange = { path, kind -> kindOf.value = kindOf.value + (path to kind) },
                    onDone = { onSave(buildServer()) },
                    onBack = { step = 1 },
                )
            }
        }
    }
}

/** 左栏：品牌 + 说明。没有服务器时这是用户见到的第一屏，要解释清楚「为什么需要 NAS」。 */
@Composable
private fun IntroPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandLogo(showWordmark = true, markSize = D.LogoMarkAbout)
        VSpace(D.X2l)
        TvText("连接你的 NAS", style = T.Headline, color = C.Text1)
        VSpace(D.Xl)
        TvText(
            text = "ElevenTV 只读取局域网里 SMB 共享或 WebDAV 目录上的视频，不上传、不转码、不依赖任何在线服务。",
            style = T.Body2,
            color = C.Text2,
        )
        VSpace(D.X2l)
        IntroBullet("1", "选协议（SMB / WebDAV），填地址与路径")
        IntroBullet("2", "浏览并勾选要收录的目录，可多选")
        IntroBullet("3", "给每个目录指定类型（Movie → 电影）")
    }
}

@Composable
private fun IntroBullet(index: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.Lg),
        modifier = Modifier.padding(vertical = D.Sm),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .background(C.Brand.copy(alpha = 0.18f), CircleShape),
        ) {
            TvText(index, style = T.Micro.copy(fontWeight = FontWeight(700)), color = C.Brand2)
        }
        TvText(text, style = T.Body2, color = C.Text2, maxLines = 1)
    }
}

/** 三步进度点：连接 → 选目录 → 指定类型 */
@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(D.Md)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .width(if (i == step) 22.dp else 8.dp)
                    .height(3.dp)
                    .background(if (i <= step) C.Brand else C.Ink3, CircleShape),
            )
        }
    }
}

@Composable
private fun ConnectionForm(
    name: String,
    onName: (String) -> Unit,
    protocol: StorageProtocol,
    onProtocol: (StorageProtocol) -> Unit,
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    share: String,
    onShare: (String) -> Unit,
    basePath: String,
    onBasePath: (String) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    pass: String,
    onPass: (String) -> Unit,
    testing: Boolean,
    valid: Boolean,
    error: String?,
    onSubmit: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize()) {
        ProtocolPicker(selected = protocol, onSelect = onProtocol)
        VSpace(D.Xl)
        Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
            Box(Modifier.weight(1f)) {
                TvTextField(
                    value = host,
                    onValueChange = onHost,
                    label = "服务器地址",
                    placeholder = "192.168.1.10 或 nas.local",
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            Box(Modifier.width(110.dp)) {
                TvTextField(
                    value = port,
                    onValueChange = onPort,
                    label = "端口",
                    placeholder = protocol.defaultPort.toString(),
                    numberOnly = true,
                )
            }
        }

        VSpace(D.Xl)

        if (protocol == StorageProtocol.SMB) {
            TvTextField(
                value = share,
                onValueChange = onShare,
                label = "共享名",
                placeholder = "media",
            )
        } else {
            TvTextField(
                value = basePath,
                onValueChange = onBasePath,
                label = "路径前缀（可留空）",
                placeholder = "留空 = 服务器根，如 media",
            )
            VSpace(D.Sm)
            // 填共享名而不是留空：留空会把根下所有共享（含 Download、home）一起扫进来，
            // 而且文件相对路径会多出一层共享名，与 SMB 时期的路径对不上
            TvText(
                text = "前缀填共享文件夹名（如 media）。威联通在「控制台 → 应用服务 → WebDAV」启用，" +
                    "端口 5005（HTTP）/ 5006（HTTPS）",
                style = T.Caption,
                color = C.Text3,
                maxLines = 2,
            )
        }

        VSpace(D.Xl)

        Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
            Box(Modifier.weight(1f)) {
                TvTextField(
                    value = user,
                    onValueChange = onUser,
                    label = "用户名（可留空）",
                    placeholder = "guest",
                )
            }
            Box(Modifier.weight(1f)) {
                TvTextField(
                    value = pass,
                    onValueChange = onPass,
                    label = "密码（可留空）",
                    placeholder = "••••••",
                    password = true,
                    onSubmit = onSubmit,
                )
            }
        }

        VSpace(D.Xl)

        TvTextField(
            value = name,
            onValueChange = onName,
            label = "显示名称（可留空）",
            placeholder = "客厅 NAS",
        )

        if (error != null) {
            VSpace(D.Xl)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(D.Md),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_alert),
                    contentDescription = null,
                    tint = C.Error,
                    modifier = Modifier.size(13.dp),
                )
                TvText(error, style = T.Body2, color = C.Error, maxLines = 2)
            }
        }

        VSpace(D.X2l)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
        ) {
            TvButton(
                onClick = onSubmit,
                label = "测试连接",
                icon = if (testing) null else R.drawable.ic_globe,
                variant = TvButtonVariant.Primary,
                enabled = valid && !testing,
                loading = testing,
            )
            if (!valid) {
                TvText(
                    text = if (protocol == StorageProtocol.WEBDAV) "地址为必填" else "地址与共享名为必填",
                    style = T.Caption,
                    color = C.Text3,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 第三步：给选中的目录指定类型。
 *
 * Movie 文件夹标成「电影」、Tv 标成「剧集」，扫描器就不再靠文件名猜；
 * 不确定就留「自动」，行为与旧版一致。
 */
@Composable
private fun KindMapper(
    paths: List<String>,
    kinds: Map<String, MediaKind?>,
    onChange: (String, MediaKind?) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { doneFocus.requestFocus() } }

    val options = listOf("auto" to "自动") +
        MediaKind.entries.map { it.name to it.label }

    Column(modifier = Modifier.fillMaxSize()) {
        TvText("指定目录类型", style = T.Title2, color = C.Text1)
        VSpace(D.Xs)
        TvText(
            text = "告诉 ElevenTV 每个目录放的是什么，内容就会归到对应分类；不确定选「自动」",
            style = T.Caption,
            color = C.Text3,
            maxLines = 1,
        )

        VSpace(D.Xl)

        LazyColumn(
            contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(D.Md),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(paths, key = { it }) { path ->
                ChoiceRow(
                    label = "\\" + path.replace("/", "\\"),
                    options = options,
                    selectedId = kinds[path]?.name ?: "auto",
                    onSelect = { id ->
                        onChange(path, if (id == "auto") null else MediaKind.valueOf(id))
                    },
                )
            }
        }

        VSpace(D.Xl)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
        ) {
            Box(Modifier.focusRequester(doneFocus)) {
                TvButton(
                    onClick = onDone,
                    label = "完成",
                    icon = R.drawable.ic_check,
                    variant = TvButtonVariant.Primary,
                )
            }
            TvButton(
                onClick = onBack,
                label = "上一步",
                icon = R.drawable.ic_chevron_left,
                variant = TvButtonVariant.Ghost,
            )
        }
    }
}

/**
 * 目录选择（多选）。
 *
 * 遥控器语义：**OK = 勾选/取消勾选**，**方向右键 = 进入该文件夹**，上键在列表顶部回「上一级」。
 * 这样一棵树里任意深度的文件夹都能直接勾上，不用逐层进入再退出。
 * 一个都不勾 = 收录整个共享。
 */
@Composable
private fun FolderPicker(
    path: String,
    entries: List<SmbEntry>,
    selected: Set<String>,
    loading: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onUp: () -> Unit,
    onPick: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val pickFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { pickFocus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
        ) {
            TvText("选择目录", style = T.Title2, color = C.Text1)
            TvText(
                text = "/" + path,
                style = T.Body2,
                color = C.Text2,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (path.isNotEmpty()) {
                TvButton(
                    onClick = onUp,
                    label = "上一级",
                    icon = R.drawable.ic_chevron_left,
                    variant = TvButtonVariant.Ghost,
                )
            }
        }

        VSpace(D.Xs)
        TvText(
            text = "OK 勾选 / 取消　·　方向右键进入　·　已选 ${selected.size} 个目录（不选则收录整个共享）",
            style = T.Caption,
            color = C.Text3,
            maxLines = 1,
        )

        VSpace(D.Xl)

        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.Mint, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                }

                error != null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TvText(error, style = T.Body2, color = C.Error, maxLines = 2)
                    VSpace(D.Xl)
                    TvButton(onClick = onRetry, label = "重试", icon = R.drawable.ic_refresh)
                }

                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TvText(
                        text = "这个目录下没有子文件夹",
                        style = T.Body2,
                        color = C.Text2,
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(D.Sm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        val childPath = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                        val picked = childPath in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(D.Lg),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(C.Ink1, RoundedCornerShape(D.RadiusField))
                                .onKeyEvent { e ->
                                    // 右键进入；OK 留给勾选
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                        onOpen(childPath)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                .focusRing(
                                    onClick = { onToggle(childPath) },
                                    scale = 1.0f,
                                    shape = RoundedCornerShape(D.RadiusField),
                                )
                                .padding(horizontal = D.Xl),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = C.Text2,
                                modifier = Modifier.size(14.dp),
                            )
                            TvText(entry.name, style = T.Body2, color = C.Text1, maxLines = 1, modifier = Modifier.weight(1f))
                            if (picked) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = C.Mint,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        VSpace(D.Xl)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
        ) {
            Box(Modifier.focusRequester(pickFocus)) {
                TvButton(
                    onClick = onPick,
                    label = if (selected.isEmpty()) "收录整个共享" else "完成（已选 ${selected.size} 个）",
                    icon = R.drawable.ic_check,
                    variant = TvButtonVariant.Primary,
                    enabled = !loading && error == null,
                )
            }
            TvButton(
                onClick = onBack,
                label = "返回修改",
                icon = R.drawable.ic_chevron_left,
                variant = TvButtonVariant.Ghost,
            )
        }
    }
}
