package com.eleventv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T

/**
 * 弹窗。
 *
 * ## 焦点
 *
 * 打开时焦点**必须**落在主操作上（[autoFocus]），关闭后由系统恢复到来源元素。
 * TV 上没有鼠标，弹窗打开却不给焦点，用户只能按 Back 逃出去。
 *
 * ## 为什么自己画而不是用 Material Dialog
 *
 * Material 的默认弹窗宽度、圆角、背景色都来自 Material 主题，
 * 与这套影院黑 + 32dp 圆角的体系不一致，改起来比自绘还费。
 */
@Composable
fun TvDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = 480,
    /**
     * 底部操作区。
     *
     * 默认为 null，并且声明在 [content] **之前**：这样 `TvDialog(...) { ... }` 的尾随 lambda
     * 永远落到 content 上。需要底部按钮的调用方用命名参数 `actions = { }` 传入。
     */
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = modifier
                .width(width.dp)
                .background(C.Ink2, RoundedCornerShape(12.dp))
                .padding(D.X2l),
        ) {
            TvText(title, style = T.Title1, color = C.Text1)
            VSpace(D.Xl)
            // 内容区必须可滚动，否则字段一多就顶出屏幕。
            //
            // 之前「服务器编辑」弹窗就是这么坏掉的：协议/地址/端口/路径前缀被挤到屏幕上方
            // 之外（截图上直接从「用户名」开始），焦点却还会往那些看不见的控件上走——
            // 用户的感觉是「焦点跑到不知道哪里去了、下面的按钮选不到」。
            // weight(1f) + verticalScroll：可用高度 = 弹窗高度减去标题与操作区，
            // 超出部分滚动；Compose 在焦点移动时会自动把目标滚进可视区。
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
            if (actions != null) {
                VSpace(D.X2l)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(D.Lg, Alignment.End),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions()
                }
            }
        }
    }
}

/**
 * 二次确认（清除刮削、删除服务器、退出应用这类不可撤销或"手滑就没了"的操作）。
 *
 * [focusConfirmByDefault] 默认 true（用户点"删除"进到这里，多半就是要确认）；
 * **退出应用要传 false**——那个弹窗是"误触返回键"的产物，焦点再落在"退出"上，
 * 等于给了第二次误触机会。
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    focusConfirmByDefault: Boolean = true,
) {
    val cancelFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    TvDialog(title = title, onDismiss = onDismiss, width = 420) {
        TvText(text, style = T.Body2, color = C.Text2)

        Row(
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.weight(1f))
            Box(
                modifier = Modifier.focusRequester(cancelFocus),
            ) {
                TvButton(
                    onClick = onDismiss,
                    label = "取消",
                    variant = TvButtonVariant.Ghost,
                )
            }
            Box(
                modifier = Modifier.focusRequester(confirmFocus),
            ) {
                TvButton(
                    onClick = onConfirm,
                    label = confirmLabel,
                    variant = if (danger) TvButtonVariant.Ghost else TvButtonVariant.Primary,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            (if (focusConfirmByDefault) confirmFocus else cancelFocus).requestFocus()
        }
    }
}

/** 单行文本提示弹窗 */
@Composable
fun MessageDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    TvDialog(title = title, onDismiss = onDismiss, width = 440) {
        TvText(text, style = T.Body2, color = C.Text2)
        Row(
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.focusRequester(focusRequester)) {
                    TvButton(onClick = onDismiss, label = "知道了", variant = TvButtonVariant.Primary)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** 输入弹窗：设置页改 TMDB Key 这类单字段编辑 */
@Composable
fun InputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
    password: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    TvDialog(title = title, onDismiss = onDismiss, width = 520) {
        Box(Modifier.focusRequester(focusRequester)) {
            TvTextField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                placeholder = placeholder,
                password = password,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VSpace(D.Xl)
        Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
            TvButton(onClick = onDismiss, label = "取消", variant = TvButtonVariant.Ghost)
            TvButton(onClick = onConfirm, label = "保存", variant = TvButtonVariant.Primary)
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}
