package com.eleventv.tv.ui.design

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 品牌组合法则：图形 H : 字标 = 3 : 2，间距 = 0.25 × H。
 *
 * 这组断言守的是「一处改、四处跟随」。真机上的老毛病是各写各的：
 * 导航栏 32/24（1.33）、启动页 96/41（2.34）、空态 40/14（2.86），
 * 同一个品牌在不同页面成了三种比例。
 */
class BrandLockupTest {

    private val base = Density(density = 1f, fontScale = 1f)

    @Test
    fun `四处标志的字号都落在 3 比 2 上`() {
        val marks = listOf(
            D.LogoMark,        // 导航栏 32dp
            D.LogoMarkLarge,   // 空态 40dp
            D.LogoMarkAbout,   // 引导页 / 关于页 28dp
            D.LogoMarkSplash,  // 启动页 96dp
        )
        marks.forEach { mark ->
            val expected = mark.value / 1.5f
            val actual = T.brandWordmark(mark, base).fontSize.value
            assertEquals(
                "标志 ${mark.value}dp 的字标应为 ${expected}sp（3:2），实际 ${actual}sp",
                expected,
                actual,
                0.01f,
            )
        }
    }

    @Test
    fun `间距随图形等比，不是固定值`() {
        assertEquals("32dp 标志 → 8dp 间距", 8f, D.lockGap(D.LogoMark).value, 0.001f)
        assertEquals("40dp 标志 → 10dp 间距", 10f, D.lockGap(D.LogoMarkLarge).value, 0.001f)
        assertEquals("28dp 标志 → 7dp 间距", 7f, D.lockGap(D.LogoMarkAbout).value, 0.001f)
        assertEquals("96dp 标志 → 24dp 间距", 24f, D.lockGap(D.LogoMarkSplash).value, 0.001f)
    }

    @Test
    fun `系统字体放大后字标仍与图形等高`() {
        // 直接用 .sp 的话，fontScale 一变字就比图形大，组合会散。
        // brandWordmark 走 toSp()，缩放后回到同一个像素高度。
        val big = Density(density = 1f, fontScale = 2f)
        val sp = T.brandWordmark(D.LogoMark, big).fontSize.value
        assertEquals("fontScale=2 时字号应减半以保持像素高度", 32f / 1.5f / 2f, sp, 0.01f)

        // 换算回像素后与 1 倍时一致
        val pxAt1 = with(base) { T.brandWordmark(D.LogoMark, base).fontSize.toPx() }
        val pxAt2 = with(big) { T.brandWordmark(D.LogoMark, big).fontSize.toPx() }
        assertEquals("两档字体缩放下的实际像素高度必须相同", pxAt1, pxAt2, 0.01f)
    }

    @Test
    fun `行高跟随字号，字标不会被裁`() {
        val style = T.brandWordmark(D.LogoMarkSplash, base)
        assertEquals(
            "行高应为字号的 1.25 倍，避免 64sp 字标被行盒裁掉上下缘",
            style.fontSize.value * 1.25f,
            style.lineHeight.value,
            0.01f,
        )
    }
}
