package com.eleventv.tv.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 启动动画节奏的回归用例。
 *
 * 「顺不顺」只能在电视上用肉眼看，但**衔接关系本身是确定性的**：
 * 字母不能比位移先动、最后一个字母要跟着位移一起收、整套必须在预算内走完。
 * 调时间参数时最容易破坏的正是这三条（改一个数就散），所以在 JVM 上钉住。
 *
 * 断言不写死 0.20 / 0.66 这类具体数——那样一调参就得改测试，人就会去删测试。
 * 这里只断言"谁先谁后、差多少以内"，具体数值留给实现自己定。
 */
class SplashMotionTest {

    private val last = SplashMotion.LETTER_COUNT - 1

    private fun assertClose(what: String, expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue("$what：期望 ≈$expected，实际 $actual", abs(expected - actual) <= eps)
    }

    /** 从 0 收到 1，返回第一个满足条件的时刻；没有则返回 NaN */
    private fun firstT(predicate: (Float) -> Boolean): Float {
        var t = 0f
        while (t <= 1f) {
            if (predicate(t)) return t
            t += 0.001f
        }
        return Float.NaN
    }

    @Test
    fun `起点时标志居中不动、字母全不可见`() {
        assertClose("起始位移", 0f, SplashMotion.slide(0f))
        for (i in 0 until SplashMotion.LETTER_COUNT) {
            assertClose("第 $i 个字母不该一上来就出现", 0f, SplashMotion.letter(0f, i))
        }
    }

    @Test
    fun `位移单调不回弹`() {
        var prev = SplashMotion.slide(0f)
        var t = 0f
        while (t <= 1f) {
            val cur = SplashMotion.slide(t)
            assertTrue("t=$t 位移回退了（$prev → $cur）", cur >= prev - 1e-4f)
            prev = cur
            t += 0.002f
        }
    }

    @Test
    fun `字母依次出现而不是同时出现`() {
        // 位移中段：前面的字母已经出来，末尾的还没
        val t = 0.40f
        for (i in 0 until last) {
            assertTrue(
                "t=$t 时第 $i 个字母应不晚于第 ${i + 1} 个",
                SplashMotion.letter(t, i) >= SplashMotion.letter(t, i + 1) - 1e-4f,
            )
        }
        assertTrue("该时刻第一个字母应已可见", SplashMotion.letter(t, 0) > 0f)
        assertTrue("该时刻最后一个字母应还没落位", SplashMotion.letter(t, last) < 1f)
    }

    @Test
    fun `字母比位移晚起步，避免名字先于标志动`() {
        val slideStart = firstT { SplashMotion.slide(it) > 0f }
        val letterStart = firstT { SplashMotion.letter(it, 0) > 0f }
        assertTrue("扫不到位移起点", !slideStart.isNaN())
        assertTrue("扫不到字母起点", !letterStart.isNaN())
        assertTrue(
            "第一个字母($letterStart) 不应早于位移起步($slideStart)",
            letterStart > slideStart,
        )
    }

    @Test
    fun `最后一个字母与位移几乎同时收尾`() {
        val slideEnd = firstT { SplashMotion.slide(it) >= 0.999f }
        val letterEnd = firstT { SplashMotion.letter(it, last) >= 0.999f }
        assertTrue("扫不到位移收尾", !slideEnd.isNaN())
        assertTrue("扫不到字母收尾", !letterEnd.isNaN())
        assertTrue("字母不该早于位移收尾", letterEnd >= slideEnd - 1e-3f)
        // 位移先停、最后一个字母后落，这个空档给 72ms（1000ms 预算的 6%）。
        // 太大就成了"标志不动了、字还在往外冒"；太小则像一起弹出来、没有先后。
        assertTrue(
            "收尾相差 ${letterEnd - slideEnd}（${(letterEnd - slideEnd) * 1000}ms），超过 60ms",
            letterEnd - slideEnd <= 0.06f,
        )
    }

    @Test
    fun `预算用完时全部落位`() {
        assertClose("末态位移", 1f, SplashMotion.slide(1f))
        for (i in 0 until SplashMotion.LETTER_COUNT) {
            assertClose("第 $i 个字母末态", 1f, SplashMotion.letter(1f, i))
        }
    }

    @Test
    fun `越界时间被钳制`() {
        assertClose("超出预算", 1f, SplashMotion.slide(2f))
        assertClose("负时间", 0f, SplashMotion.slide(-1f))
        assertClose("字母越界", 1f, SplashMotion.letter(9f, last))
    }

    // ---------------------------------------------------------------------
    // 节奏：这一组守的是"看得到"。
    //
    // 真机反馈：动画能播了，但"动画之前 LOGO 停太久"。那段时间里，
    // 前一半是系统启动窗口（改不了），后一半是这一层自己干等的时长——可以改。
    // ---------------------------------------------------------------------

    @Test
    fun `上屏就起步，不在开头干等`() {
        val start = firstT { SplashMotion.slide(it) > 0f }
        assertTrue("扫不到位移起点", !start.isNaN())
        assertTrue(
            "位移到进度 $start（${start * 1000}ms）才起步，超过 100ms 就会看出「停了一下」",
            start * 1000f <= 100f,
        )
    }

    @Test
    fun `相邻字母间隔在能看清的区间`() {
        val gap = (firstT { SplashMotion.letter(it, 1) > 0f } -
            firstT { SplashMotion.letter(it, 0) > 0f }) * 1000f
        assertTrue("间隔 ${gap}ms 太小，8 个字会糊成同时点亮", gap >= 60f)
        assertTrue("间隔 ${gap}ms 太大，动画会被拖长", gap <= 120f)
    }

    // ---------------------------------------------------------------------
    // 帧推进：这一组守的是"动画不能被跳过"。
    //
    // 真机上的症状：LOGO 停两秒，下一个画面已经是完整的 LOGO + ElevenTV。
    // 原因是首页挂载那一帧把主线程占住了，真实帧间隔是一两秒而不是 16ms；
    // 只要这帧被如实计入，时间轴一步就到终点。
    //
    // 曾经在这里放过口子（超支 2.5 倍就放开限幅），结果恰好是卡得最狠的那一帧
    // 满足条件、直接跳过去——所以下面专门钉住"无论卡多久都不放开"。
    // ---------------------------------------------------------------------

    @Test
    fun `卡死一帧后动画不会直接跳到终点`() {
        val budget = 1000f
        val step = SplashMotion.step(realDeltaMs = 2000f, budgetMs = budget)
        // 上限是 MAX_FRAME_MS/budget：预算 1000ms 时即 10%，够走 10 帧，绝不是"一帧到底"
        assertTrue("单帧推进了 ${step * 100}%，等于把动画跳过去了", step <= 0.12f)
        assertTrue("单帧必须仍有推进，否则卡一下就永远停住", step > 0f)
    }

    @Test
    fun `卡多久都不放开限幅`() {
        // 这是上一版真机失败的根因：主线程卡 4 秒，动画当帧就落终态。
        val budget = 1000f
        for (delta in listOf(200f, 500f, 2000f, 4000f, 10_000f)) {
            val step = SplashMotion.step(delta, budget)
            assertTrue(
                "帧间隔 ${delta}ms 时单帧推进了 ${step * 100}%，超过 12% 就会看出跳帧",
                step <= 0.12f,
            )
        }
        // 无论间隔多大，推进量都封顶在同一个值
        assertClose(
            "超大帧间隔必须与 100ms 完全一致",
            SplashMotion.MAX_FRAME_MS / budget,
            SplashMotion.step(10_000f, budget),
        )
    }

    @Test
    fun `正常帧率下动画仍在预算内走完`() {
        // 60fps 不该被限幅影响，否则一改 MAX_FRAME_MS 就会把动画拖长。
        val budget = 1000f
        var t = 0f
        var frames = 0
        while (t < 1f && frames < 10_000) {
            t += SplashMotion.step(16.7f, budgetMs = budget)
            frames++
        }
        assertClose("走完所需帧数", 60f, frames.toFloat(), eps = 3f)
    }

    @Test
    fun `帧间隔为负不会被当成倒退`() {
        // 系统时钟被调整时可能出现；推进量必须是 0 而不是负数。
        assertClose("时钟回退", 0f, SplashMotion.step(-50f, 1000f))
    }
}
