package com.eleventv.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T
import com.eleventv.tv.ui.design.focusRing
import com.eleventv.tv.ui.design.glassBorder

/** 按钮变体。每屏最多一个 [Primary]。 */
enum class TvButtonVariant { Primary, Ghost, Icon }

/**
 * 主按钮 / 次按钮 / 图标按钮。
 *
 * 高 32dp 胶囊，左右 17dp，图标 11dp，图标与文字间距 6dp（`tokens.json → component.button`）。
 *
 * @param loading 加载中：图标换成 spinner，禁用点击但**保持可聚焦**——
 *   焦点在加载中被挤走，用户会以为界面卡死。
 * @param enabled false 时 40% 不透明且不可聚焦。
 */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    @DrawableRes icon: Int? = null,
    variant: TvButtonVariant = TvButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String? = null,
) {
    val bg = when (variant) {
        TvButtonVariant.Primary -> C.Text1
        else -> C.GlassStrong
    }
    val fg = when (variant) {
        TvButtonVariant.Primary -> C.Ink0
        else -> C.Text1
    }
    val alpha = if (enabled) 1f else 0.4f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.ButtonGap),
        modifier = modifier
            .height(D.ButtonH)
            .background(bg.copy(alpha = bg.alpha * alpha), CircleShape)
            .then(
                if (variant == TvButtonVariant.Primary) Modifier
                else Modifier.glassBorder(C.StrokeGlass, 1f, CircleShape),
            )
            // focusRing 必须在 padding 之前：Compose 修饰符由外向内生效，
            // 挂在 padding 内侧的话焦点环只包住内容（文字），不包整个胶囊——
            // 「紫色边框宽度错误」第二次上机的真正根因就是这里。
            .focusRing(
                onClick = { if (!loading) onClick() },
                scale = 1.04f,
                shape = CircleShape,
                enabled = enabled,
            )
            .padding(horizontal = if (variant == TvButtonVariant.Icon) D.Lg else D.ButtonPadH)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(D.ButtonIcon),
                color = fg,
                strokeWidth = 1.5.dp,
            )

            icon != null -> Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = fg.copy(alpha = alpha),
                modifier = Modifier.size(D.ButtonIcon),
            )
        }
        if (!label.isNullOrBlank()) {
            TvText(
                text = label,
                style = T.Body2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)),
                color = fg.copy(alpha = alpha),
                maxLines = 1,
            )
        }
    }
}
