package com.eleventv.tv.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 统一焦点视觉：2dp 白内描边 + 2.5dp 品牌光晕 + 可选缩放。
 *
 * 全应用**只有这一种焦点视觉**，组件不得自己实现。
 *
 * ## 为什么缩放走 graphicsLayer
 *
 * 改布局尺寸会触发整行重新测量，遥控器快速移动时表现为抖动。graphicsLayer 只影响绘制。
 *
 * ## 为什么 clickable 与 focusable 二选一
 *
 * 两者都自带焦点节点，同时挂会生成两个焦点目标（按一次 OK 要按两次才生效）。
 * 有 onClick 时用 clickable（它已包含 focusable 语义），否则用 focusable。
 *
 * @param onClick 传 null 表示这个元素只接收焦点、不响应 OK（少用）
 * @param scale 焦点时放大倍数，1f 表示不缩放
 * @param highlighted 外部状态直接点亮焦点环，**不走 Compose 焦点**。
 *   用于按键由 Activity 分发、遥控器方向键根本到不了 Compose 焦点系统的页面
 *   （典型：播放器错误页的「重试/退出」）——焦点视觉仍由这里统一画，调用方只传个布尔。
 *   默认 false，其它走正常 Compose 焦点的路径行为不变。
 */
fun Modifier.focusRing(
    onClick: (() -> Unit)? = null,
    scale: Float = 1f,
    shape: Shape = RoundedCornerShape(D.RadiusPoster),
    enabled: Boolean = true,
    highlighted: Boolean = false,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    // Compose 焦点点亮，或被外部 highlighted 直接点亮，二者取一；enabled 关掉时都不亮
    val showFocus = (focused || highlighted) && enabled

    val currentScale by animateFloatAsState(
        targetValue = if (showFocus) scale else 1f,
        animationSpec = tween(
            durationMillis = if (showFocus) M.FOCUS_IN else M.FOCUS_OUT,
            easing = M.Standard,
        ),
        label = "focusScale",
    )

    this
        .onFocusChanged { focused = enabled && it.isFocused }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
            } else {
                Modifier.focusable(enabled = enabled)
            },
        )
        .graphicsLayer {
            scaleX = currentScale
            scaleY = currentScale
        }
        .drawWithContent {
            drawContent()
            if (!showFocus) return@drawWithContent
            drawFocusVisuals(shape)
        }
}

/**
 * 只画焦点框、不接管焦点事件的版本。
 *
 * 用于「焦点由父级管理、子级只负责显示」的场景（例如整行开关内部）。
 */
fun Modifier.focusBorder(focused: Boolean, shape: Shape = RoundedCornerShape(D.RadiusPoster)): Modifier =
    drawWithContent {
        drawContent()
        if (!focused) return@drawWithContent
        drawFocusVisuals(shape)
    }

/**
 * 焦点视觉的绘制规则（tokens：2dp 白环 + 2.5dp 品牌光晕）。
 *
 * ## 为什么不能居中描边
 *
 * `Stroke` 以轮廓线为中心向两侧各扩一半：2dp 白环只剩 1dp 露在外面，
 * 5dp 光晕却有 2.5dp 叠进按钮内容里——之前「紫色边框宽度错误」就是这个原因。
 * 所以白环整体内缩到边界内侧（2dp 全部可见、不啃内容），
 * 光晕整体外扩到边界外侧（2.5dp 全部落在按钮外，宽度才和 tokens 一致）。
 */
private fun DrawScope.drawFocusVisuals(shape: Shape) {
    val ringPx = D.FocusRing.toPx()
    val glowPx = D.FocusGlow.toPx()

    // 白环：轮廓内缩 ringPx，描边宽 2×ringPx → 精确覆盖内侧 [0, 2dp]
    if (size.width > ringPx * 2f && size.height > ringPx * 2f) {
        val inner = shape.createOutline(
            Size(size.width - ringPx * 2f, size.height - ringPx * 2f),
            layoutDirection,
            this,
        )
        translate(ringPx, ringPx) {
            drawOutline(inner, C.FocusRing, style = Stroke(width = ringPx * 2f))
        }
    }

    // 光晕：轮廓外扩 glowPx/2，描边宽 glowPx → 精确覆盖外侧 [0, 2.5dp]
    val outer = shape.createOutline(
        Size(size.width + glowPx, size.height + glowPx),
        layoutDirection,
        this,
    )
    translate(-glowPx / 2f, -glowPx / 2f) {
        drawOutline(outer, C.FocusGlow, style = Stroke(width = glowPx))
    }
}

/**
 * 纯色描边辅助（非焦点态的分隔线、玻璃边）。与焦点环同理：整体画在边界内侧，宽度所见即所得。
 *
 * [width] 的单位是 **dp**（调用方都传 `1f`），必须先换算成 px 再参与几何计算。
 * 以前一半按 px、一半按 dp：`inset = width / 2f` 当成 px（1dp 只有 0.5px），
 * 而 `Stroke(width = width * density)` 是 dp→px，环的位置比线宽偏了 1px 左右。
 */
fun Modifier.glassBorder(color: Color = C.StrokeGlass, width: Float = 1f, shape: Shape): Modifier =
    drawWithContent {
        drawContent()
        val w = width * density
        if (size.width <= w || size.height <= w) return@drawWithContent
        val outline = shape.createOutline(
            Size(size.width - w, size.height - w),
            layoutDirection,
            this,
        )
        translate(w / 2f, w / 2f) {
            drawOutline(outline, color, style = Stroke(width = w))
        }
    }
