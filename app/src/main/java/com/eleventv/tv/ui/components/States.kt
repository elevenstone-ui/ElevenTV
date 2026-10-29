package com.eleventv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eleventv.tv.R
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T

/**
 * 全屏状态页（空 / 错误 / 离线）。
 *
 * `states.md` 的硬规则：**每个状态都必须有一个可聚焦元素**。遥控器没有鼠标，
 * 一个只有图标和文字的错误页会把用户彻底卡死，只能按 Home 退出。
 * 所以 [action] 是必填参数，不是可选装饰。
 */
@Composable
fun StateView(
    title: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = R.drawable.ic_alert,
    description: String? = null,
    errorCode: String? = null,
    brand: Boolean = false,
    action: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(C.Ink0),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (brand) {
                BrandLogo(showWordmark = true, markSize = D.LogoMarkLarge)
                VSpace(D.X2l)
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = C.Text3,
                    modifier = Modifier.size(44.dp),
                )
                VSpace(D.X2l)
            }

            TvText(title, style = T.Title1, color = C.Text1, textAlign = TextAlign.Center)

            description?.takeIf { it.isNotBlank() }?.let {
                VSpace(D.Md)
                TvText(
                    text = it,
                    style = T.Body2,
                    color = C.Text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(420.dp),
                )
            }

            errorCode?.takeIf { it.isNotBlank() }?.let {
                VSpace(D.Sm)
                TvText(it, style = T.Caption, color = C.Text3, textAlign = TextAlign.Center)
            }

            VSpace(D.X3l)
            action()
        }
    }
}
