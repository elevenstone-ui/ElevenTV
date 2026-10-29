package com.eleventv.tv.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.T

/** 统一文字入口：默认不走 Material 的 onSurface 颜色，避免每处都写 colorScheme。 */
@Composable
fun TvText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = T.Body1,
    color: Color = C.Text1,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        textAlign = textAlign,
        overflow = overflow,
    )
}

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
