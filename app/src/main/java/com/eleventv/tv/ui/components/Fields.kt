package com.eleventv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T
import com.eleventv.tv.ui.design.focusBorder
import com.eleventv.tv.ui.design.focusRing
import com.eleventv.tv.ui.design.glassBorder

/**
 * 单行输入框。
 *
 * TV 上没有触摸，输入靠系统输入法（遥控器方向键选字母）。所以：
 * 获得焦点时主动唤起 IME，失焦不动——避免弹窗关闭后 IME 还挂在那里挡画面。
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    password: Boolean = false,
    numberOnly: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onSubmit: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(D.RadiusField)

    Column(modifier = modifier) {
        label?.let {
            TvText(it, style = T.Caption, color = C.Text2, maxLines = 1)
            VSpace(D.Xs)
        }

        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(if (focused) C.Ink3 else C.Glass, shape)
                .glassBorder(if (focused) C.StrokeGlass else C.StrokeHairline, 1f, shape)
                // 焦点由内部的 BasicTextField 持有，这里只负责画框：
                // 父节点的 onFocusChanged 会收到子节点获得焦点的事件
                .focusBorder(focused, shape)
                .onFocusChanged {
                    // 必须用 hasFocus 而不是 isFocused：这个 Box 自身没有焦点目标，
                    // 子节点（BasicTextField）拿到焦点时它只会变成 hasFocus。
                    // 用 isFocused 的话永远是 false —— 聚焦态不亮，键盘也不会弹出来。
                    focused = it.hasFocus
                    if (it.hasFocus) keyboard?.show()
                }
                .padding(horizontal = D.Lg),
        ) {
            if (value.isEmpty()) {
                TvText(placeholder, style = T.Body2, color = C.Text3, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = T.Body2.copy(color = C.Text1),
                cursorBrush = SolidColor(C.Mint),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numberOnly) KeyboardType.Number else KeyboardType.Text,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }, onNext = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 整行开关。
 *
 * label 与开关在**同一个可聚焦区域**，聚焦时整行高亮——不是只有那个小拨杆能按。
 * 开关图形自绘：Material 的 Switch 在 TV 上 32dp 偏小，且水波纹会与焦点环打架。
 */
@Composable
fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(D.RadiusPoster)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(if (description.isNullOrBlank()) 44.dp else 52.dp)
            .background(if (focused) C.Glass else Color.Transparent, shape)
            // onFocusChanged 必须在 focusRing **之前**：focusRing 内部会把
            // clickable + onFocusChanged 串成焦点链，挂在其后就在焦点目标内侧，
            // 收不到事件 —— 表现是白环正常但整行底色高亮永远不出现
            .onFocusChanged { focused = it.hasFocus }
            .focusRing(onClick = onToggle, scale = 1.0f, shape = shape)
            .padding(horizontal = D.Lg),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TvText(label, style = T.Body2, color = C.Text1, maxLines = 1)
            description?.takeIf { it.isNotBlank() }?.let {
                TvText(it, style = T.Caption, color = C.Text3, maxLines = 1)
            }
        }
        SwitchTrack(checked)
    }
}

/** 自绘开关轨道：24×13dp，滑块 10dp */
@Composable
fun SwitchTrack(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(D.SwitchTrackW, D.SwitchTrackH)
            .background(
                if (checked) C.Brand else Color.White.copy(alpha = 0.16f),
                CircleShape,
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 1.5.dp)
                .size(D.SwitchKnob)
                .background(if (checked) C.Text1 else C.Text2, CircleShape),
        )
    }
}

/**
 * 单选行：2–6 个选项横向铺开，选中段白底黑字。
 *
 * 再多或文案过长时应该改用列表，这里不做自动降级——调用方决定。
 * （6 个两字选项约 300dp，放进设置卡片仍留得下半屏，不会把左侧标题挤没。）
 */
@Composable
fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = D.Lg),
    ) {
        TvText(label, style = T.Body2, color = C.Text1, modifier = Modifier.weight(1f), maxLines = 1)

        Row(horizontalArrangement = Arrangement.spacedBy(D.Sm)) {
            options.forEach { (id, text) ->
                val selected = id == selectedId
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(D.ChipH)
                        .background(if (selected) C.Text1 else C.Glass, CircleShape)
                        .focusRing(onClick = { onSelect(id) }, scale = 1.0f, shape = CircleShape)
                        .padding(horizontal = D.ChipPadH),
                ) {
                    TvText(
                        text = text,
                        style = T.Caption.copy(fontWeight = if (selected) FontWeight(700) else FontWeight(400)),
                        color = if (selected) C.Ink0 else C.Text2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 字幕样式预览。
 *
 * 字号只能在「真实尺寸」下判断：设置页里一行字看着够大，坐到三米外完全不是那么回事，
 * 于是每次都得进播放页试、退出来改。这里按播放页**完全相同**的算法画两句样品
 * （Title1 × 倍数、行高同步缩放、底衬不透明度），看到的就是电视上会看到的。
 *
 * 两句而不是一句：行距是「小字号刚好、大字号叠字」的翻车点，一行样品看不出来。
 */
@Composable
fun SubtitlePreview(
    scale: Float,
    bgAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val base = T.Title1
    // 与 PlayerScreen.SubtitleLayer 保持一致（含行高缩放），改那边记得同步这里
    val style = base.copy(
        fontSize = base.fontSize * scale,
        lineHeight = base.lineHeight * scale,
    )
    val ratio = if (scale == scale.toInt().toFloat()) "${scale.toInt()}×" else "${scale}×"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(148.dp)
            // 先裁再画底：字号调到最大时文字会顶到边框，溢出会在卡片上留下字影
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B0D10)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            listOf("这就是电视上的字幕大小", "第二行用来看行距").forEach { line ->
                Box(
                    modifier = Modifier
                        .padding(vertical = 1.dp)
                        .background(Color.Black.copy(alpha = bgAlpha), RoundedCornerShape(4.dp))
                        .padding(horizontal = D.Md, vertical = 3.dp),
                ) {
                    TvText(text = line, style = style, color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
        TvText(
            text = ratio,
            style = T.Caption,
            color = C.Text3,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = D.Lg, vertical = D.Md),
        )
    }
}

/** 只读信息行（版本号、路径之类） */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = D.Lg, vertical = D.Sm),
    ) {
        TvText(label, style = T.Body2, color = C.Text2, modifier = Modifier.width(160.dp), maxLines = 1)
        TvText(value, style = T.Body2, color = C.Text1, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

/** 分区卡片：标题 + 内容，设置页统一用它 */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(C.Ink1, RoundedCornerShape(12.dp))
            .padding(D.X2l),
    ) {
        TvText(title, style = T.Title2, color = C.Text1)
        VSpace(D.Lg)
        content()
    }
}
