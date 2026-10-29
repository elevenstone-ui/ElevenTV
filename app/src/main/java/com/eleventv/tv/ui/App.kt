package com.eleventv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.MediaFile
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.repo.ElevenRepository
import com.eleventv.tv.data.repo.toSummary
import com.eleventv.tv.ui.components.ConfirmDialog
import com.eleventv.tv.ui.components.KeyHintBar
import com.eleventv.tv.ui.components.NavItem
import com.eleventv.tv.ui.components.TvNavBar
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.screens.BrowseScreen
import com.eleventv.tv.ui.screens.CollectionScreen
import com.eleventv.tv.ui.screens.DetailScreen
import com.eleventv.tv.ui.screens.HomeScreen
import com.eleventv.tv.ui.screens.OnboardingScreen
import com.eleventv.tv.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/** 目的地。TV 应用只有这么几个，不需要引 Navigation-Compose。 */
sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class Browse(val kind: MediaKind) : Screen
    data class Detail(val entryId: String) : Screen

    /**
     * 同标签海报墙。
     *
     * [genre] 与 [person] 二选一（都为空时页面就是空的）：
     * 前者来自详情页的类型标签，后者来自演员卡。
     *
     * 当前停在「电影」还是「剧集」不放在这里：那是用户在这一屏里的操作状态，
     * 由路由层的 `collectionGroupMovies` 保管，进出详情页不会丢。
     */
    data class Collection(
        val label: String,
        val genre: String? = null,
        val person: String? = null,
    ) : Screen

    data object Settings : Screen
}

/**
 * 应用外壳与路由。
 *
 * ## 启动流程
 *
 * 没有服务器 → 添加 SMB 服务器引导；有服务器 → 直接进首页。
 * 进首页后若开了「启动时自动扫描」，后台自动扫一遍并接着刮削；
 * 手动触发入口保留在设置页。
 */
@Composable
fun AppRoot(
    repo: ElevenRepository,
    onPlayFile: (LibraryEntry, MediaFile) -> Unit,
    /** 根页面确认退出后真正结束 Activity */
    onExitApp: () -> Unit = {},
    /**
     * 是否接管返回键。
     *
     * 启动页显示期间传 false，由外层把返回吃掉。
     * 之前靠"后注册的 BackHandler 优先"来做这件事，但这里面的返回处理器
     * 要等首页挂载（第二帧）才注册，注册顺序和书写顺序相反，时不时就抢错。
     * 现在两边用**互斥的 enabled**，谁生效只取决于状态，不取决于顺序。
     */
    backEnabled: Boolean = true,
) {
    val ready by repo.ready.collectAsState()
    val servers by repo.servers.collectAsState()
    val settings by repo.settings.collectAsState()
    val library by repo.library.collectAsState()
    val playback by repo.playback.collectAsState()
    val scanJob by repo.scanJob.collectAsState()
    val scrapeJob by repo.scrapeJob.collectAsState()

    val scope = rememberCoroutineScope()
    var stack by remember { mutableStateOf<List<Screen>>(emptyList()) }

    /**
     * 从详情页返回时要把焦点还给哪个条目。
     *
     * 导航是自己维护的栈，切换页面时内容树整体重建、页面内部状态（滚动位置、焦点）
     * 不保留，焦点会落到组合里最靠前的可聚焦元素——顶部菜单。用户点开一张海报、
     * 看一眼详情再退回来，遥控器就跑到菜单上了，得重新往下找。
     * 所以在这里记下「用户点开的是哪一张」，返回首页/分类页时由列表把它滚进视野并聚焦。
     */
    var returnFocusId by remember { mutableStateOf<String?>(null) }

    /**
     * 同标签海报墙当前停在哪个分组（true = 电影）。
     *
     * 放在路由层而不是海报墙内部：从墙里点进详情再退回来时，页面整体重建，
     * 局部状态会丢，用户刚切到的「剧集」会跳回「电影」。
     */
    var collectionGroupMovies by remember { mutableStateOf(false) }

    // 首次载入完成后决定入口；添加完服务器后自动从引导页进首页
    LaunchedEffect(ready, servers.size) {
        if (!ready) return@LaunchedEffect
        val entry = if (servers.isEmpty()) Screen.Onboarding else Screen.Home
        when {
            stack.isEmpty() -> stack = listOf(entry)
            stack.last() == Screen.Onboarding && servers.isNotEmpty() -> stack = listOf(Screen.Home)
        }
    }

    // 启动时自动扫描：**每次启动只触发一次**。
    // 以前 key 里带 servers.size，于是新增/删除一台服务器就会立刻整库重扫（扫描完还会
    // 接着自动刮削），和设置页那句「启动时自动扫描」的说明对不上，也白烧网络。
    var autoScanStarted by remember { mutableStateOf(false) }
    LaunchedEffect(ready, servers.isNotEmpty()) {
        if (!ready || servers.isEmpty() || autoScanStarted) return@LaunchedEffect
        autoScanStarted = true
        if (settings.autoScanOnStart) repo.scan()
    }

    val current = stack.lastOrNull() ?: return

    fun go(target: Screen) {
        when (target) {
            // 主动切到顶层页面时清掉恢复意图：否则焦点会莫名跳到某张旧卡上
            Screen.Home, Screen.Settings, Screen.Onboarding -> {
                returnFocusId = null
                stack = listOf(target)
            }

            else -> {
                // 入栈详情页才记录「退出后焦点该回到哪」
                if (target is Screen.Detail) returnFocusId = target.entryId
                stack = stack + target
            }
        }
    }

    /**
     * 根页面按返回时的退出确认。
     *
     * 遥控器的返回键和方向键挨得近，以前在首页误触一下就退回电视桌面，还得重新启动、
     * 等启动页、等扫描。改成二次确认，且**默认焦点落在「取消」**上：误触再误按一次 OK
     * 也只是回到界面，不会直接退出。
     */
    var confirmExit by remember { mutableStateOf(false) }

    // 一个返回处理器覆盖全部层级，别拆成多个：Compose 的 BackHandler 是"后注册者优先"，
    // 两个都处于 enabled 时会互相盖掉，行为取决于书写顺序，很容易改出 regress。
    //
    // 判定顺序：先问"是不是在首页"（唯一谈退出的地方），再逐层回退。
    // 三个踩过的坑，改动时别再踩回去：
    // 1. 别拿栈深度判断"是否在根部"——顶层页面入栈时 `go()` 会把栈重置成
    //    `listOf(设置页)`，size 就是 1，`size > 1` 不成立；
    // 2. 别把 enabled 绑到 `!confirmExit` 上——弹窗开着时这个 handler 整个失效，
    //    TV 上 Compose Dialog 没拿到焦点时收不到返回键，按键会穿透到 Activity，
    //    dispatcher 里一个 enabled 的都没有，系统就按默认行为把应用关了；
    // 3. 引导页必须显式吞掉返回，否则同样会穿透。
    BackHandler(enabled = backEnabled) {
        when {
            // 退出框开着时，返回等于取消
            confirmExit -> confirmExit = false
            // 只有首页才谈"退出应用"
            current == Screen.Home -> confirmExit = true
            // 引导页没有可退的地方，吞掉即可
            current == Screen.Onboarding -> Unit
            // 其余一切页面（设置页、详情页、海报墙、分类页）：有上一页就回上一页，没有就回首页
            stack.size > 1 -> stack = stack.dropLast(1)
            else -> stack = listOf(Screen.Home)
        }
    }

    val showNav = current is Screen.Home || current is Screen.Browse || current is Screen.Settings
    val navSelected = when (current) {
        Screen.Home -> "home"
        is Screen.Browse -> current.kind.name.lowercase()
        Screen.Settings -> "settings"
        else -> "home"
    }

    // 首页行由「媒体库 + 播放记录」计算，任一变化都要重算
    val homeRows = remember(library, playback) { repo.homeRows() }
    val browseItems = remember(library, playback, current) {
        (current as? Screen.Browse)?.let { screen ->
            repo.titlesOf(screen.kind).map { entry ->
                entry.toSummary(playback.firstOrNull { it.entryId == entry.id })
            }
        }.orEmpty()
    }

    // 同标签海报墙：电影与剧集分开算，页面里再用标签切换
    val collection = current as? Screen.Collection
    val collectionMovies = remember(library, playback, collection) {
        collection?.let { filter ->
            repo.titlesOfCollection(filter, moviesOnly = true).map { entry ->
                entry.toSummary(playback.firstOrNull { it.entryId == entry.id })
            }
        }.orEmpty()
    }
    val collectionSeries = remember(library, playback, collection) {
        collection?.let { filter ->
            repo.titlesOfCollection(filter, moviesOnly = false).map { entry ->
                entry.toSummary(playback.firstOrNull { it.entryId == entry.id })
            }
        }.orEmpty()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Ink0),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (showNav) {
                TvNavBar(
                    selectedId = navSelected,
                    onSelect = { item: NavItem ->
                        when (item.id) {
                            "home" -> go(Screen.Home)
                            "settings" -> go(Screen.Settings)
                            else -> MediaKind.entries
                                .firstOrNull { it.name.lowercase() == item.id }
                                ?.let { go(Screen.Browse(it)) }
                        }
                    },
                )
            }

            Box(Modifier.weight(1f)) {
                when (current) {
                    Screen.Onboarding -> OnboardingScreen(
                        onTest = { server -> repo.testServer(server) },
                        onSave = { server -> scope.launch { repo.saveServer(server) } },
                        onBrowse = { server, path -> repo.browse(server, path) },
                    )

                    Screen.Home -> HomeScreen(
                        rows = homeRows,
                        libraryEmpty = library.entries.isEmpty(),
                        scanning = scanJob,
                        scraping = scrapeJob,
                        hasServers = servers.isNotEmpty(),
                        onOpenTitle = { summary -> go(Screen.Detail(summary.id)) },
                        onViewAll = { kind -> go(Screen.Browse(kind)) },
                        onScan = { scope.launch { repo.scan() } },
                        onScrape = { scope.launch { repo.scrapeAll() } },
                        onAddServer = { go(Screen.Settings) },
                        focusId = returnFocusId,
                        onFocusHandled = { returnFocusId = null },
                    )

                    is Screen.Browse -> BrowseScreen(
                        kind = current.kind,
                        items = browseItems,
                        onOpenTitle = { summary -> go(Screen.Detail(summary.id)) },
                        onBack = { go(Screen.Home) },
                        focusId = returnFocusId,
                        onFocusHandled = { returnFocusId = null },
                    )

                    is Screen.Detail -> DetailScreen(
                        entry = library.entries.firstOrNull { it.id == current.entryId },
                        record = playback.firstOrNull { it.entryId == current.entryId },
                        onBack = { stack = stack.dropLast(1) },
                        onPlay = { entry, file -> onPlayFile(entry, file) },
                        onRescrape = { scope.launch { repo.scrapeOne(current.entryId) } },
                        onOpenGenre = { genre ->
                            val source = library.entries.firstOrNull { it.id == current.entryId }
                            collectionGroupMovies = source?.kind == MediaKind.MOVIE
                            go(Screen.Collection(label = genre, genre = genre))
                        },
                        onOpenCast = { name ->
                            val source = library.entries.firstOrNull { it.id == current.entryId }
                            collectionGroupMovies = source?.kind == MediaKind.MOVIE
                            go(Screen.Collection(label = name, person = name))
                        },
                    )

                    is Screen.Collection -> CollectionScreen(
                        label = current.label,
                        kindLabel = if (current.person != null) "演员" else "类型",
                        movieItems = collectionMovies,
                        seriesItems = collectionSeries,
                        initialMovies = collectionGroupMovies,
                        onOpenTitle = { summary -> go(Screen.Detail(summary.id)) },
                        onBack = { stack = stack.dropLast(1) },
                        focusId = returnFocusId,
                        onFocusHandled = { returnFocusId = null },
                        onGroupChange = { collectionGroupMovies = it },
                    )

                    Screen.Settings -> SettingsScreen(
                        servers = servers,
                        settings = settings,
                        scanJob = scanJob,
                        scrapeJob = scrapeJob,
                        entryCount = library.entries.size,
                        scrapedCount = library.entries.count { it.scraped },
                        onTest = { server -> repo.testServer(server) },
                        onSaveServer = { server -> scope.launch { repo.saveServer(server) } },
                        onDeleteServer = { id -> scope.launch { repo.deleteServer(id) } },
                        onScan = { scope.launch { repo.scan() } },
                        onScrapeAll = { force -> scope.launch { repo.scrapeAll(force) } },
                        onClearScraped = { scope.launch { repo.clearScraped() } },
                        onDiagnose = {
                            com.eleventv.tv.data.scrape.ScrapeDiagnostics.run(settings)
                        },
                        onUpdateSettings = { transform -> scope.launch { repo.updateSettings(transform) } },
                    )
                }
            }
        }

        if (current != Screen.Onboarding) {
            KeyHintBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        if (confirmExit) {
            ConfirmDialog(
                title = "退出 ElevenTV？",
                text = "将回到电视桌面。媒体库与播放进度都已保存。",
                confirmLabel = "退出",
                // 默认焦点在「取消」：误触返回键之后随手再按一下 OK，也只是关掉这个弹窗
                focusConfirmByDefault = false,
                onConfirm = {
                    confirmExit = false
                    onExitApp()
                },
                onDismiss = { confirmExit = false },
            )
        }
    }
}

/**
 * 同标签海报墙的取数入口：类型标签和演员共用一条路径。
 *
 * 名字（而不是 TMDB 人物 id）作为筛选键：剧集与电影分属两套 credits，
 * 同一个人在两边的 id 可能不同，但名字是稳定的。
 */
private fun ElevenRepository.titlesOfCollection(
    filter: Screen.Collection,
    moviesOnly: Boolean,
): List<LibraryEntry> {
    filter.person?.let { return titlesOfCast(it, moviesOnly) }
    filter.genre?.let { return titlesOfGenre(it, moviesOnly) }
    return emptyList()
}
