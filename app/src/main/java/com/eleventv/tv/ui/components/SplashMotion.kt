package com.eleventv.tv.ui.components

/**
 * 启动动画时间表：标志位移与「ElevenTV」逐字母出现的节奏。
 *
 * 单独成文件、**不引用任何 Compose 类型**，是为了让节奏本身能被单元测试直接跑：
 * 什么时候起步、第几个字母什么时候落下、收尾是否齐平——这些原本只能在电视上用肉眼判断，
 * 现在由 `SplashMotionTest` 钉住。
 *
 * 全部用「预算的比例」而不是绝对毫秒：预算固定为 `M.SPLASH_ANIM`(1000ms)，
 * 不随首帧耗时伸缩——否则同一份节奏在不同机器上会被拉长或压扁。
 *
 * 编排（括号内为预算比例）：
 *
 *     0.00 ── 0.06   标志居中不动（只剩 60ms，交接感，不是"停留"）
 *     0.06 ── 0.74   整组左移
 *     0.10 ── 0.71   8 个字母依次淡入
 *
 * 位移比第一个字母早 0.04 起步、比最后一个字母早 0.04 收尾，所以看起来是
 * 「标志滑开、名字跟着长出来」的一件事，而不是两段各走各的动画。
 *
 * 开头那段静止被压到 0.06，是因为真机上它和窗口背景连在一起看：
 * 系统启动窗口里那张静态 LOGO 已经站了一两秒，Compose 这一层再"站" 0.20（旧版 280ms）
 * 就变成了"点完图标，LOGO 杵在那儿不动"。现在一上屏就起步。
 */
internal object SplashMotion {

    /** 字标。拆成单字符逐个出现，字母数即布局里的子项数 */
    const val WORDMARK = "ElevenTV"

    val LETTER_COUNT: Int = WORDMARK.length

    /**
     * 位移起点。旧版是 0.20——首帧上屏后还要干等 280ms 才动，
     * 与前面的系统启动窗口连起来看就是「LOGO 杵着不动」。现在 60ms 就起步。
     */
    private const val SLIDE_START = 0.06f

    /** 位移时长（0.06 → 0.74）。收尾必须和最后一个字母几乎同时，差 110ms 就会看出"标志停了、字还在冒" */
    private const val SLIDE_LEN = 0.68f

    /** 第一个字母的起点：比位移晚 0.04，让眼睛先看到「标志在动」，再看到「字在出」 */
    private const val LETTER_START = 0.10f

    /**
     * 相邻字母的间隔。8 个字走完占 0.504，按 1000ms 预算即每个字间隔 72ms。
     *
     * 这值与位移时长绑定：调大会把最后一个字母推到位移停稳之后太久，
     * 看起来像"字还没出完、标志已经不动了"。SplashMotionTest 里有专门的用例卡这个。
     */
    private const val LETTER_STEP = 0.072f

    /** 单个字母的淡入时长。比间隔长一些，是连续长出而不是一个一个蹦 */
    private const val LETTER_LEN = 0.11f

    /** 光晕涨满的时间点 */
    private const val GLOW_LEN = 0.32f

    /** 标志缩放整段（1.0 → 1.03 → 1.0）占的比例 */
    private const val SCALE_LEN = 0.68f

    /** 缩放的峰值位置（0.68 × 0.45 ≈ 0.31） */
    private const val SCALE_PEAK = 0.45f

    /** 峰值放大倍数。超过 1.05 会在交接瞬间看出来"换了一层在画" */
    private const val SCALE_AMOUNT = 0.03f

    /**
     * 单帧最多计入的真实时长。**永不放开**。
     *
     * 这是"动画不会被跳过"的唯一保证。电视盒子冷启动时首页要组海报墙、量几十张卡片，
     * 主线程一卡就是一两秒；那一帧的真实间隔是 2000ms 而不是 16ms。
     * 只要这一帧被如实计入，`2000 / 1000 = 2`，时间轴一步就到终点——
     * 用户看到的就是「LOGO 停一下，下一帧已经是完整画面」。
     *
     * 限幅后每帧最多推进 `100 / budget ≈ 7%`：掉帧只会把动画抻长，绝不会跳过去。
     *
     * 上一版在这里放过口子（"超支 2.5 倍就放开限幅，免得拖太久"），结果正好在
     * 主线程卡得最狠的那一帧失效——**卡得越久越有可能被跳过**，与初衷完全相反。
     * 现在改为永不放开的硬上限，时长由调用方的墙钟兜底负责（见 `BrandSplash`）。
     */
    const val MAX_FRAME_MS = 100f

    /**
     * 主相位终点：位移已停稳、最后一个字母已落位。
     *
     * 过了这一点，剩下的只有光晕常亮和底部进度线的收尾，即使掉帧也看不出来。
     * `MainActivity` 拿它当"可以开始搭首页"的信号——首页的测量非常重，早一步挂载
     * 就会把动画挤成幻灯片。
     */
    const val MAIN_PHASE_END = 0.72f

    /**
     * 位移进度：0 = 标志居中，1 = 整组居中。
     *
     * 曲线在这里手写而不是引用 `M.Decelerate`：这段逻辑要能被单元测试直接跑，
     * 不能依赖 Compose 运行时。手感一致，都是「起步快、收尾贴住」。
     */
    fun slide(t: Float): Float = easeOutCubic(window(t, SLIDE_START, SLIDE_LEN))

    /**
     * 第 [index] 个字母的浮现进度：0 = 完全透明（且处在最低点上浮位置），1 = 落位。
     *
     * 用二次缓出而不是线性——线性 alpha 看着像开关灯，缓出才有「浮现」感。
     * 上浮距离由调用方按 dp 换算，这里只给比例。
     */
    fun letter(t: Float, index: Int): Float =
        easeOutQuad(window(t, LETTER_START + index * LETTER_STEP, LETTER_LEN))

    /** 光晕强度：0.00 ── 0.39 涨满，此后常亮。跟着标志一起走，所以也由时间轴派生 */
    fun glow(t: Float): Float = easeOutCubic(window(t, 0f, GLOW_LEN))

    /**
     * 标志缩放。起止都是 1.0，与启动窗口里那颗 96dp 标志严丝合缝，交接时看不出换层。
     *
     * 中间轻涨到 1.03 再落回（三角波，峰值在 0.28 处），替代更早版本"放大落位"的做法——
     * 那种会让 LOGO 在窗口交接的瞬间先跳大一点再缩回去。
     */
    fun scale(t: Float): Float {
        val p = window(t, 0f, SCALE_LEN)
        val tri = if (p <= SCALE_PEAK) p / SCALE_PEAK else (1f - p) / (1f - SCALE_PEAK)
        return 1f + SCALE_AMOUNT * tri
    }

    /**
     * 一帧的时间轴推进量（0..1 总进度的比例）。
     *
     * 单帧最多只算 [MAX_FRAME_MS] 毫秒，**没有任何例外**。
     * 真实帧间隔再大，时间轴也只往前走一小步，下一帧必定还会被画出来；
     * 设备整段都慢，结果是动画变长，而不是变没。
     *
     * @param realDeltaMs 这一帧距上一帧的真实间隔
     * @param budgetMs 原定总时长
     */
    fun step(realDeltaMs: Float, budgetMs: Float): Float =
        realDeltaMs.coerceIn(0f, MAX_FRAME_MS) / budgetMs

    /** 把线性进度切成 `[start, start + len]` 的窗口，返回窗口内的 0..1 */
    private fun window(t: Float, start: Float, len: Float): Float =
        ((t - start) / len).coerceIn(0f, 1f)

    private fun easeOutCubic(p: Float): Float {
        val q = 1f - p
        return 1f - q * q * q
    }

    private fun easeOutQuad(p: Float): Float {
        val q = 1f - p
        return 1f - q * q
    }
}
