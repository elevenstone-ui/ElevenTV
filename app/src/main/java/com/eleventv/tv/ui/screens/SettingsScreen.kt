package com.eleventv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.eleventv.tv.BuildConfig
import com.eleventv.tv.R
import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.Decoder
import com.eleventv.tv.data.model.JobState
import com.eleventv.tv.data.model.JobStatus
import com.eleventv.tv.data.model.MediaRoot
import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.model.StorageProtocol
import com.eleventv.tv.ui.components.BrandLogo
import com.eleventv.tv.ui.components.BrandWordmark
import com.eleventv.tv.ui.components.ChoiceRow
import com.eleventv.tv.ui.components.ConfirmDialog
import com.eleventv.tv.ui.components.InfoRow
import com.eleventv.tv.ui.components.InputDialog
import com.eleventv.tv.ui.components.MessageDialog
import com.eleventv.tv.ui.components.ProtocolPicker
import com.eleventv.tv.ui.components.SettingsCard
import com.eleventv.tv.ui.components.SubtitlePreview
import com.eleventv.tv.ui.components.ToggleRow
import com.eleventv.tv.ui.components.TvButton
import com.eleventv.tv.ui.components.TvButtonVariant
import com.eleventv.tv.ui.components.TvDialog
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
 * 设置页。
 *
 * 所有写操作都通过 [onUpdateSettings] 的 transform 走仓储，页面不持有配置副本——
 * 一边扫描一边改设置时，两处状态必须始终是同一份。
 */
@Composable
fun SettingsScreen(
    servers: List<SmbServer>,
    settings: AppSettings,
    scanJob: JobState,
    scrapeJob: JobState,
    entryCount: Int,
    scrapedCount: Int,
    /** 未刮削条目的原始标题，供「未命中清单」对话框取证 */
    unmatched: List<String>,
    onTest: suspend (SmbServer) -> Result<SmbServer>,
    onSaveServer: (SmbServer) -> Unit,
    onDeleteServer: (String) -> Unit,
    onScan: () -> Unit,
    /** 停止当前扫描；运行时「扫描媒体库」按钮会变成「停止扫描」并调用它 */
    onStopScan: () -> Unit,
    onScrapeAll: (Boolean) -> Unit,
    onClearScraped: () -> Unit,
    onDiagnose: suspend () -> List<com.eleventv.tv.data.scrape.ScrapeDiagnostics.Item>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var editor by remember { mutableStateOf<SmbServer?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SmbServer?>(null) }
    var clearingConfirm by remember { mutableStateOf(false) }
    var keyEdit by remember { mutableStateOf<KeyEdit?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var diagnosing by remember { mutableStateOf(false) }

    val scanRunning = scanJob.status == JobStatus.Running
    val scrapeRunning = scrapeJob.status == JobStatus.Running

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(C.Ink0),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = D.ScreenPaddingX,
                end = D.ScreenPaddingX,
                top = D.Xl,
                // 「关于」是最后一项：底部要留够让整张卡片滚到可视区中部，
                // 避开叠在 BottomCenter 的 KeyHintBar（28dp，进页面后 5 秒才淡出）
                // 以及电视过扫描裁掉的下边缘。
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(D.X2l),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ---------------- 媒体库 ----------------
            item("library") {
                SettingsCard(title = "媒体库") {
                    InfoRow("已识别条目", "$entryCount 部")
                    InfoRow("已刮削", "$scrapedCount / $entryCount")

                    VSpace(D.Md)
                    JobLine(scanJob, "扫描", C.Mint)
                    JobLine(scrapeJob, "刮削", C.Brand)

                    VSpace(D.Xl)
                    Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
                        // 扫描进行中把这个按钮变成「停止扫描」：TvButton 的 loading 会
                        // 吞掉点击、enabled=false 会让它不可聚焦，所以运行时必须
                        // loading=false / enabled=true，否则遥控器根本按不到停止。
                        TvButton(
                            onClick = if (scanRunning) onStopScan else onScan,
                            label = if (scanRunning) "停止扫描" else "扫描媒体库",
                            icon = if (scanRunning) R.drawable.ic_close else R.drawable.ic_scan,
                            enabled = true,
                            loading = false,
                        )
                        TvButton(
                            onClick = { onScrapeAll(false) },
                            label = "刮削未完成",
                            icon = R.drawable.ic_scrape,
                            variant = TvButtonVariant.Ghost,
                            enabled = !scrapeRunning && entryCount > 0,
                            loading = scrapeRunning,
                        )
                        TvButton(
                            onClick = { onScrapeAll(true) },
                            label = "全部重新刮削",
                            icon = R.drawable.ic_refresh,
                            variant = TvButtonVariant.Ghost,
                            enabled = !scrapeRunning && entryCount > 0,
                        )
                    }
                }
            }

            // ---------------- 服务器 ----------------
            item("servers") {
                SettingsCard(title = "网络存储（SMB / WebDAV）") {
                    if (servers.isEmpty()) {
                        TvText(
                            text = "还没有服务器。添加后才能扫描媒体库。",
                            style = T.Body2,
                            color = C.Text3,
                        )
                        VSpace(D.Lg)
                    }

                    servers.forEach { server ->
                        ServerBlock(
                            server = server,
                            onTest = {
                                scope.launch {
                                    val r = onTest(server)
                                    message = if (r.isSuccess) {
                                        "连接成功" to "${server.display} / ${server.share} 可以访问。"
                                    } else {
                                        "连接失败" to (r.exceptionOrNull()?.message ?: "未知错误")
                                    }
                                }
                            },
                            onEdit = {
                                editor = server
                                editorOpen = true
                            },
                            onDelete = { deleting = server },
                        )
                    }

                    VSpace(D.Sm)
                    TvButton(
                        onClick = {
                            editor = null
                            editorOpen = true
                        },
                        label = "添加服务器",
                        icon = R.drawable.ic_plus,
                        variant = TvButtonVariant.Primary,
                    )
                }
            }

            // ---------------- 刮削 ----------------
            item("scrape") {
                SettingsCard(title = "刮削") {
                    ToggleRow(
                        label = "启用刮削",
                        description = "关闭后只按文件名整理，不联网获取海报与简介",
                        checked = settings.scrapeEnabled,
                        onToggle = { onUpdateSettings { s -> s.copy(scrapeEnabled = !s.scrapeEnabled) } },
                    )
                    ToggleRow(
                        label = "扫描完成后自动刮削",
                        description = "新条目自动补齐元数据",
                        checked = settings.autoScrapeAfterScan,
                        onToggle = { onUpdateSettings { s -> s.copy(autoScrapeAfterScan = !s.autoScrapeAfterScan) } },
                    )
                    ToggleRow(
                        label = "刮削海报",
                        description = "TMDB / 豆瓣的竖版封面",
                        checked = settings.scrapePoster,
                        onToggle = { onUpdateSettings { s -> s.copy(scrapePoster = !s.scrapePoster) } },
                    )
                    ToggleRow(
                        label = "刮削背景图",
                        description = "详情页与「继续播放」卡使用的横版剧照",
                        checked = settings.scrapeBackdrop,
                        onToggle = { onUpdateSettings { s -> s.copy(scrapeBackdrop = !s.scrapeBackdrop) } },
                    )
                    ToggleRow(
                        label = "刮削季简介",
                        description = "多季剧集每季单独一段",
                        checked = settings.scrapeSeasonOverview,
                        onToggle = { onUpdateSettings { s -> s.copy(scrapeSeasonOverview = !s.scrapeSeasonOverview) } },
                    )
                    ToggleRow(
                        label = "刮削单集简介",
                        description = "选集列表里显示每集标题与简介",
                        checked = settings.scrapeEpisodeOverview,
                        onToggle = { onUpdateSettings { s -> s.copy(scrapeEpisodeOverview = !s.scrapeEpisodeOverview) } },
                    )
                    ToggleRow(
                        label = "优先中文标题",
                        description = "TMDB 有中文译名时优先使用",
                        checked = settings.preferChineseTitle,
                        onToggle = { onUpdateSettings { s -> s.copy(preferChineseTitle = !s.preferChineseTitle) } },
                    )

                    VSpace(D.Lg)
                    KeyRow(
                        label = "TMDB API Key",
                        value = settings.tmdbApiKey,
                        onEdit = { keyEdit = KeyEdit("TMDB API Key", settings.tmdbApiKey) { s, v -> s.copy(tmdbApiKey = v) } },
                    )
                    KeyRow(
                        label = "TMDB 读访问令牌",
                        value = settings.tmdbReadToken,
                        onEdit = { keyEdit = KeyEdit("TMDB 读访问令牌", settings.tmdbReadToken) { s, v -> s.copy(tmdbReadToken = v) } },
                    )
                    KeyRow(
                        label = "Fanart API Key",
                        value = settings.fanartApiKey,
                        onEdit = { keyEdit = KeyEdit("Fanart API Key", settings.fanartApiKey) { s, v -> s.copy(fanartApiKey = v) } },
                    )

                }
            }

            // ---------------- 刮削操作 ----------------
            // 必须单独成一个 item，不能塞进上面那张「刮削」卡的末尾。
            //
            // 刮削卡有 7 个开关 + 3 个凭证行，整卡内容 ~576dp，高于电视一屏。
            // LazyColumn 的滚动以 item 为单位：item 比视口还高时，bringIntoView
            // 没法把「item 内部靠下的节点」滚进视野，焦点系统在按 ↓ 时会直接跳过
            // 它们、落到下一张卡 —— 表现为「网络诊断 / 清除刮削结果」两颗按钮
            // 焦点上不去、←→ 也切不了，而「关于」卡因为自己是独立 item 才滚得动。
            // 拆成独立 item 后高度远小于视口，焦点可以正常跨 item 落到按钮上。
            item("scrapeActions") {
                SettingsCard(title = "刮削操作") {
                    KeyRow(
                        label = "图片反代前缀（可选）",
                        value = settings.imageBaseUrl,
                        onEdit = {
                            keyEdit = KeyEdit("图片反代前缀", settings.imageBaseUrl) { s, v ->
                                s.copy(imageBaseUrl = v)
                            }
                        },
                    )
                    ToggleRow(
                        label = "豆瓣海报优先",
                        description = "TMDB 图片 CDN 连不上时，用可达的豆瓣海报兜底",
                        checked = settings.preferDoubanPoster,
                        onToggle = {
                            onUpdateSettings { s -> s.copy(preferDoubanPoster = !s.preferDoubanPoster) }
                        },
                    )
                    VSpace(D.Lg)
                    Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
                        TvButton(
                            onClick = {
                                diagnosing = true
                                scope.launch {
                                    val items = onDiagnose()
                                    diagnosing = false
                                    message = "刮削网络诊断" to items.joinToString("\n") {
                                        (if (it.ok) "✓ " else "✗ ") + it.name + "：" + it.detail
                                    }
                                }
                            },
                            label = "网络诊断",
                            icon = R.drawable.ic_globe,
                            variant = TvButtonVariant.Ghost,
                            enabled = !diagnosing,
                            loading = diagnosing,
                        )
                        TvButton(
                            onClick = { clearingConfirm = true },
                            label = "清除刮削结果",
                            icon = R.drawable.ic_close,
                            variant = TvButtonVariant.Ghost,
                            enabled = scrapedCount > 0,
                        )
                        TvButton(
                            onClick = {
                                message = "未命中清单（${unmatched.size} 条）" to
                                    if (unmatched.isEmpty()) {
                                        "全部条目都已刮削"
                                    } else {
                                        "每行：失败原因 ｜ 当前标题 ｜ 文件所在目录名 ｜ 判定类型。\n" +
                                            "已按失败原因排序，同类问题连在一起；原因来自上一轮刮削，\n" +
                                            "若显示「本轮未记录原因」，请先跑一次刮削再看。\n\n" +
                                            unmatched.joinToString("\n") { "· $it" }
                                    }
                            },
                            label = "未命中清单",
                            icon = R.drawable.ic_metadata,
                            variant = TvButtonVariant.Ghost,
                        )
                    }
                }
            }

            // ---------------- 播放 ----------------
            item("player") {
                SettingsCard(title = "播放") {
                    ChoiceRow(
                        label = "解码方式",
                        options = Decoder.entries.map { it.name to it.label },
                        selectedId = settings.decoder.name,
                        onSelect = { id ->
                            val d = Decoder.entries.firstOrNull { it.name == id } ?: Decoder.Auto
                            onUpdateSettings { s -> s.copy(decoder = d) }
                        },
                    )
                    ToggleRow(
                        label = "记忆播放进度",
                        description = "下次打开从上次位置继续",
                        checked = settings.resumePlayback,
                        onToggle = { onUpdateSettings { s -> s.copy(resumePlayback = !s.resumePlayback) } },
                    )
                    ToggleRow(
                        label = "自动播放下一集",
                        description = "当前集播完后接着播下一集",
                        checked = settings.autoNextEpisode,
                        onToggle = { onUpdateSettings { s -> s.copy(autoNextEpisode = !s.autoNextEpisode) } },
                    )
                    ToggleRow(
                        label = "启动音效",
                        description = "与启动动画配套的短音效，关闭后启动页静默",
                        checked = settings.splashSound,
                        onToggle = { onUpdateSettings { s -> s.copy(splashSound = !s.splashSound) } },
                    )
                    ChoiceRow(
                        // 原来的上限 1.5×（约 25sp）在三米外仍然偏小，用户反馈「特大不够大」。
                        // 上限放到 2.6×（约 44sp，1080p 下约占画面高度 8%），并补「超大/巨大」
                        // 两档——中间不留 0.5 的跳档，遥控器左右两下就能找到合适的那一档。
                        label = "字幕字号",
                        options = listOf(
                            "0.85" to "小",
                            "1.0" to "标准",
                            "1.25" to "大",
                            "1.5" to "特大",
                            "2.0" to "超大",
                            "2.6" to "巨大",
                        ),
                        selectedId = settings.subtitleSizeScale.toString(),
                        onSelect = { id ->
                            onUpdateSettings { s -> s.copy(subtitleSizeScale = id.toFloatOrNull() ?: 1f) }
                        },
                    )
                    ChoiceRow(
                        // 这个值播放器一直在用（字幕底衬不透明度），但以前设置页没有入口，
                        // 等于永远 0.6——亮场景下白字压不住。既然做了就让它可调。
                        label = "字幕底衬",
                        options = listOf(
                            "0.0" to "无",
                            "0.35" to "淡",
                            "0.6" to "标准",
                            "0.9" to "浓",
                        ),
                        selectedId = settings.subtitleBgAlpha.toString(),
                        onSelect = { id ->
                            onUpdateSettings { s -> s.copy(subtitleBgAlpha = id.toFloatOrNull() ?: 0.6f) }
                        },
                    )
                    VSpace(D.Md)
                    // 在设置页里按真实尺寸预演：不然只能「进播放页看 → 退出 → 改 → 再看」
                    SubtitlePreview(
                        scale = settings.subtitleSizeScale,
                        bgAlpha = settings.subtitleBgAlpha,
                    )
                    VSpace(D.Sm)
                    TvText(
                        text = "上面的字号就是播放时的实际大小，坐到沙发上看一眼再定。" +
                            "字幕位置固定贴底，调到「巨大」也不会被裁掉。",
                        style = T.Caption,
                        color = C.Text3,
                    )
                    VSpace(D.Xs)
                    TvText(
                        text = "「软件解码」使用系统自带的软件编解码器，兼容性更好但高码率 4K 可能掉帧。",
                        style = T.Caption,
                        color = C.Text3,
                    )
                }
            }

            // ---------------- 扫描规则 ----------------
            item("scanRules") {
                SettingsCard(title = "扫描规则") {
                    InfoRow("最小文件体积", "${settings.minFileSizeMb} MB 以下的文件当作样片忽略")
                    InfoRow("识别扩展名", settings.videoExtensions.joinToString("、"))
                    VSpace(D.Md)
                    ToggleRow(
                        label = "启动时自动扫描",
                        // 与实现一致：每次打开 App 扫一次（不是「库为空时才扫」）。
                        // 改服务器不会触发重扫，需要手动点「重新扫描」。
                        description = "每次打开 App 扫一遍媒体库；改服务器配置后请手动重新扫描",
                        checked = settings.autoScanOnStart,
                        onToggle = { onUpdateSettings { s -> s.copy(autoScanOnStart = !s.autoScanOnStart) } },
                    )
                }
            }

            // ---------------- 关于 ----------------
            item("about") {
                SettingsCard(
                    title = "关于",
                    // 这是 LazyColumn 最后一项，内部又全是纯展示文本 —— 一个可聚焦节点都没有。
                    // TV 上滚动靠焦点 bringIntoView：焦点到不了这张卡片，按 ↓ 就停在上一个
                    // ToggleRow，卡片下半截（含 TMDB 条款声明）永远进不了可视区。
                    // 前两次分别调了 maxLines 和底部留白，都只解决「文本被裁」，
                    // 对「根本滚不下去」无效。focusRing(onClick = null) 只接收焦点、不响应 OK，
                    // 让整张卡片成为焦点目标，按 ↓ 时把整卡带进视野。
                    modifier = Modifier.focusRing(
                        onClick = null,
                        shape = RoundedCornerShape(12.dp),
                    ),
                ) {
                    // 标志与字标同样是 3:2 的组合：28dp 标志 → 18.7sp 字标、7dp 间距。
                    // 间距不能用 D.Xl：它是内容间距，不是图形与字标的关系值。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(D.lockGap(D.LogoMarkAbout)),
                    ) {
                        BrandLogo(showWordmark = false, markSize = D.LogoMarkAbout)
                        Column {
                            BrandWordmark(D.LogoMarkAbout)
                            TvText(
                                text = "版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.BUILD_STAMP}",
                                style = T.Caption,
                                color = C.Text3,
                            )
                        }
                    }
                    VSpace(D.Lg)
                    TvText(
                        text = "支持局域网 SMB 与 WebDAV 两种网络存储，数据全部保存在本机。刮削时仅把片名发送给数据源，不上传任何文件。",
                        style = T.Body2,
                        color = C.Text2,
                    )

                    // 数据来源声明。
                    //
                    // 这不是客套话：TMDB 的 API 使用条款要求必须写明「产品使用了 TMDB API，
                    // 但未获得 TMDB 认可或认证」，Fanart.tv 同样要求署名。删掉这两行就等于
                    // 违反使用条款——而条款被判定违规的后果就是 key 被封，整个刮削直接失效。
                    VSpace(D.Xl)
                    TvText("数据来源", style = T.Label, color = C.Text3)
                    VSpace(D.Xs)
                    // 条款要求的署名不允许省略：TvText 默认 overflow=Ellipsis，
                    // 一旦限行数就会把末尾截掉，宽屏看似够用，遇到大字体/窄屏/过扫描就露馅。
                    InfoRow(
                        "TMDB",
                        "影片信息、评分与海报。本产品使用 TMDB API，但未获得 TMDB 认可或认证。",
                        valueMaxLines = Int.MAX_VALUE,
                    )
                    InfoRow(
                        "Fanart.tv",
                        "部分剧照与背景图。图片版权归原作者所有。",
                        valueMaxLines = Int.MAX_VALUE,
                    )
                    VSpace(D.Xs)
                    TvText(
                        // TMDB 品牌规范里指定的原句，保留英文原文便于对照，且不设行数上限
                        text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                        style = T.Caption,
                        color = C.Text3,
                    )
                }
            }
        }
    }

    // ---------------- 弹窗 ----------------

    if (editorOpen) {
        ServerEditorDialog(
            initial = editor,
            onTest = onTest,
            onSave = { server, correction ->
                onSaveServer(server)
                editorOpen = false
                // 自动改过就必须说一声：不然用户下次手填时还以为自己是照原样填对的
                if (correction != null) {
                    message = "已自动修正为服务器上的实际写法" to
                        "$correction\n\nWebDAV 路径区分大小写，SMB 不区分，从 SMB 切过来时最容易踩这一条。"
                }
            },
            onDismiss = { editorOpen = false },
        )
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = "删除服务器",
            text = "「${target.name}」及其媒体库条目会一并移除，已刮削的元数据也会丢失。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                onDeleteServer(target.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    if (clearingConfirm) {
        ConfirmDialog(
            title = "清除刮削结果",
            text = "所有海报、简介与评分会被清空，重新刮削需要再联网一次。",
            confirmLabel = "清除",
            danger = true,
            onConfirm = {
                onClearScraped()
                clearingConfirm = false
            },
            onDismiss = { clearingConfirm = false },
        )
    }

    keyEdit?.let { edit ->
        var draft by remember(edit) { mutableStateOf(edit.value) }
        InputDialog(
            title = edit.label,
            label = "凭证",
            value = draft,
            onValueChange = { draft = it },
            onConfirm = {
                onUpdateSettings { s -> edit.apply(s, draft.trim()) }
                keyEdit = null
            },
            onDismiss = { keyEdit = null },
            // 文案必须与实际行为一致：清空后保存确实会写进空串，
            // 而空串会让 creds() 回落到内置凭证 —— 以前写「留空表示不修改」，
            // 用户照着做，自己的 Key 就被悄悄换成了内置的，排查时极难想到
            placeholder = "清空后保存 = 删除该项凭证（回落到内置）",
        )
    }

    message?.let { (title, text) ->
        MessageDialog(title = title, text = text, onDismiss = { message = null })
    }
}

private data class KeyEdit(
    val label: String,
    val value: String,
    val apply: (AppSettings, String) -> AppSettings,
)

/** 刮削/扫描任务的一行状态。Idle 时不占地方。 */
@Composable
private fun JobLine(job: JobState, label: String, color: androidx.compose.ui.graphics.Color) {
    if (job.status == JobStatus.Idle) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.Md),
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        when (job.status) {
            JobStatus.Running -> CircularProgressIndicator(
                color = color,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(11.dp),
            )

            JobStatus.Done -> Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = C.Mint,
                modifier = Modifier.size(11.dp),
            )

            else -> Icon(
                painter = painterResource(R.drawable.ic_alert),
                contentDescription = null,
                tint = C.Error,
                modifier = Modifier.size(11.dp),
            )
        }

        TvText(
            text = buildString {
                append(label)
                if (job.status == JobStatus.Running && job.current > 0) append(" ${job.current}/${job.total}")
                val detail = job.error ?: job.message
                if (detail.isNotBlank()) append("　").append(detail)
            },
            style = T.Caption,
            color = if (job.status == JobStatus.Failed) C.Error else C.Text2,
            maxLines = 1,
        )
    }
}

/** 一条服务器：地址、共享、起始目录 + 测试/编辑/删除 */
@Composable
private fun ServerBlock(
    server: SmbServer,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.Lg),
        modifier = Modifier
            .fillMaxWidth()
            .background(C.Ink2, RoundedCornerShape(D.RadiusPoster))
            .padding(horizontal = D.Xl, vertical = D.Md),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_storage),
            contentDescription = null,
            tint = if (server.enabled) C.Mint else C.Text3,
            modifier = Modifier.size(15.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            TvText(server.name, style = T.Body2, color = C.Text1, maxLines = 1)
            TvText(
                // 协议名打头：同一台 NAS 的 SMB 与 WebDAV 条目地址形态不同（UNC vs URL），
                // 不标明时两个条目看起来一模一样，选错了表现就是「一直转圈」。
                text = when (server.protocol) {
                    StorageProtocol.SMB -> "SMB · \\\\${server.display}\\${server.share}"
                    StorageProtocol.WEBDAV -> "WebDAV · ${server.baseUrl}"
                } + server.roots.takeIf { it.isNotEmpty() }
                    ?.joinToString("、") { root ->
                        val sep = if (server.protocol == StorageProtocol.SMB) "\\" else "/"
                        sep + root.path.replace("/", sep) +
                            (root.kind?.let { "（${it.label}）" } ?: "")
                    }
                    .orEmpty(),
                style = T.Caption,
                color = C.Text3,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(D.Sm)) {
            TvButton(
                onClick = onTest,
                label = "测试",
                icon = R.drawable.ic_globe,
                variant = TvButtonVariant.Ghost,
            )
            TvButton(
                onClick = onEdit,
                label = "编辑",
                icon = R.drawable.ic_metadata,
                variant = TvButtonVariant.Ghost,
            )
            TvButton(
                onClick = onDelete,
                label = "删除",
                icon = R.drawable.ic_close,
                variant = TvButtonVariant.Ghost,
            )
        }
    }
    VSpace(D.Md)
}

/** 凭证行：只显示是否已填，不显示明文——截图分享设置页时不会泄露 */
@Composable
private fun KeyRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = D.Lg),
    ) {
        TvText(label, style = T.Body2, color = C.Text2, modifier = Modifier.weight(1f), maxLines = 1)
        TvText(
            text = if (value.isBlank()) "未填写" else "已配置 · ${value.take(4)}…${value.takeLast(4)}",
            style = T.Caption,
            color = if (value.isBlank()) C.Text3 else C.Mint,
            maxLines = 1,
        )
        Box(Modifier.width(D.Lg))
        TvButton(onClick = onEdit, label = "修改", variant = TvButtonVariant.Ghost)
    }
}

/**
 * 服务器编辑弹窗。
 *
 * 与引导页共用同一套字段，但不做目录浏览——编辑状态下用户已经知道路径，
 * 再塞一棵目录树进来只会让弹窗变得又高又难按。
 */
@Composable
private fun ServerEditorDialog(
    initial: SmbServer?,
    onTest: suspend (SmbServer) -> Result<SmbServer>,
    /** [correction] 非 null 表示测试时自动改了配置（WebDAV 路径大小写），界面要告知用户 */
    onSave: (SmbServer, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var protocol by remember(initial) { mutableStateOf(initial?.protocol ?: StorageProtocol.SMB) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) {
        mutableStateOf((initial?.port ?: protocol.defaultPort).toString())
    }
    var share by remember(initial) { mutableStateOf(initial?.share.orEmpty()) }
    var basePath by remember(initial) { mutableStateOf(initial?.basePath.orEmpty()) }
    var user by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    // 多目录的类型映射在引导页完成；弹窗里只维护路径的快速编辑，
    // 路径没变的目录保留原有类型
    var root by remember(initial) { mutableStateOf(initial?.roots?.joinToString("、") { it.path }.orEmpty()) }

    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build() = SmbServer(
        id = initial?.id ?: ("srv-" + UUID.randomUUID().toString().take(8)),
        name = name.trim().ifBlank { host.trim() },
        host = host.trim(),
        port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: protocol.defaultPort,
        // 切到 WebDAV 时**不清空** share：它虽然不再参与连接，但它是文件 id 的一段
        // （`serverId|share|path`）。清掉会让整库的 id 全变，播放记录随之失联。
        // 隐藏字段留着当 id 盐，是这里唯一刻意的取舍。
        share = share.trim().trim('/'),
        username = user.trim(),
        password = pass,
        protocol = protocol,
        // 留空时用共享名兜底：NAS 一律不允许列根目录，留空的必然结果是 403。
        // 顺带保住 id 稳定（id 里带 share），切协议不会让整库记录失配。
        basePath = if (protocol == StorageProtocol.WEBDAV) {
            basePath.trim().ifBlank { share.trim() }.trim('/')
        } else {
            ""
        },
        mediaRoots = root.split('、')
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .map { path -> MediaRoot(path, initial?.roots?.firstOrNull { it.path == path }?.kind) },
    )

    // WebDAV 不需要共享名（路径直接在 URL 里），所以必填项随协议变
    val valid = host.isNotBlank() &&
        (protocol == StorageProtocol.WEBDAV || share.isNotBlank())

    TvDialog(
        title = if (initial == null) "添加服务器" else "编辑服务器",
        onDismiss = onDismiss,
        width = 560,
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(D.Lg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TvButton(
                    onClick = {
                        testing = true
                        error = null
                        scope.launch {
                            val draft = build()
                            val r = onTest(draft)
                            testing = false
                            error = r.exceptionOrNull()?.message
                            // 保存的是**测通的那份**，不是手里这份草稿：WebDAV 会在 404 时
                            // 自动把路径大小写改成服务器实际写法，存草稿等于白测。
                            r.onSuccess { ok ->
                                // 测通即存：TV 上再点一次「保存」是多余的一步
                                onSave(ok, correctionNote(draft, ok))
                            }
                        }
                    },
                    label = "测试并保存",
                    icon = if (testing) null else R.drawable.ic_globe,
                    variant = TvButtonVariant.Primary,
                    enabled = valid && !testing,
                    loading = testing,
                )
                TvButton(
                    onClick = { onSave(build(), null) },
                    label = "直接保存",
                    variant = TvButtonVariant.Ghost,
                    enabled = valid,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    TvButton(onClick = onDismiss, label = "取消", variant = TvButtonVariant.Ghost)
                }
            }
        },
    ) {
        ProtocolPicker(
            selected = protocol,
            onSelect = { picked ->
                if (picked != protocol) {
                    // 端口还停在旧协议的默认值时才跟着切，用户手填过的端口不动
                    if (port.toIntOrNull() == protocol.defaultPort) port = picked.defaultPort.toString()
                    // SMB → WebDAV 时用共享名兜底路径前缀。
                    //
                    // NAS 的 WebDAV 把「共享文件夹」暴露成根下的第一层，所以共享名就是天然的
                    // 前缀。不补这一层，文件相对路径会从 `剧集/Show/01.mkv` 变成
                    // `media/剧集/Show/01.mkv`——扫描后文件 id 全变，播放记录、已刮削的
                    // 海报与简介全部对不上（id 由 serverId|share|path 派生）。
                    if (picked == StorageProtocol.WEBDAV && basePath.isBlank() && share.isNotBlank()) {
                        basePath = share.trim('/')
                    }
                    protocol = picked
                    error = null
                }
            },
        )
        VSpace(D.Lg)
        Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
            Box(Modifier.weight(1f)) {
                TvTextField(value = host, onValueChange = { host = it }, label = "地址", placeholder = "192.168.1.10")
            }
            Box(Modifier.width(96.dp)) {
                TvTextField(value = port, onValueChange = { port = it }, label = "端口", numberOnly = true)
            }
        }
        VSpace(D.Lg)
        if (protocol == StorageProtocol.SMB) {
            TvTextField(value = share, onValueChange = { share = it }, label = "共享名", placeholder = "media")
        } else {
            TvTextField(
                value = basePath,
                onValueChange = { basePath = it },
                label = "路径前缀（填共享名）",
                placeholder = "如 media；留空会去列 NAS 根，必然 403",
            )
        }
        VSpace(D.Lg)
        Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
            Box(Modifier.weight(1f)) {
                TvTextField(value = user, onValueChange = { user = it }, label = "用户名", placeholder = "guest")
            }
            Box(Modifier.weight(1f)) {
                TvTextField(value = pass, onValueChange = { pass = it }, label = "密码", password = true)
            }
        }
        VSpace(D.Lg)
        TvTextField(
            value = root,
            onValueChange = { root = it },
            label = "起始目录（多个用「、」分隔）",
            placeholder = "留空表示共享根目录",
        )
        VSpace(D.Lg)
        TvTextField(value = name, onValueChange = { name = it }, label = "显示名称", placeholder = "客厅 NAS")

        error?.let {
            VSpace(D.Lg)
            // 6 行：WebDAV 的 404 诊断会把「实际可用的子目录/大小写」一并带出来，
            // 4 行不够，截掉的正好是最有用的那半句
            TvText(it, style = T.Caption, color = C.Error, maxLines = 6)
        }
    }
}

/**
 * 把「测试时自动改了什么」变成一句人话。
 *
 * 静默改配置是危险的：用户下次在别处手填，仍会按自己原来那份写，然后再次踩坑。
 * 所以改了就说，说清楚从什么改成什么。
 */
private fun correctionNote(draft: SmbServer, tested: SmbServer): String? {
    val parts = buildList {
        if (tested.basePath != draft.basePath) {
            add("路径前缀「${draft.basePath.ifBlank { "空" }}」→「${tested.basePath}」")
        }
        if (tested.roots != draft.roots) {
            add(
                "起始目录「${draft.roots.joinToString("、") { it.path }}」→" +
                    "「${tested.roots.joinToString("、") { it.path }}」",
            )
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
}
