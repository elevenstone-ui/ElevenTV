package com.eleventv.tv.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 全局主题。
 *
 * 只有深色一套：影院黑体系在 OLED 上是真黑，浅色主题在这台设备上没有使用场景。
 */
@Composable
fun ElevenTheme(
    @Suppress("UNUSED_PARAMETER") dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = darkColorScheme(
        primary = C.Brand,
        onPrimary = C.Ink0,
        secondary = C.Brand2,
        onSecondary = C.Ink0,
        tertiary = C.Mint,
        background = C.Ink0,
        onBackground = C.Text1,
        surface = C.Ink1,
        onSurface = C.Text1,
        surfaceVariant = C.Ink2,
        onSurfaceVariant = C.Text2,
        surfaceContainer = C.Ink3,
        surfaceContainerHigh = C.Ink3,
        outline = C.StrokeGlass,
        outlineVariant = C.StrokeHairline,
        error = C.Error,
        onError = C.Ink0,
        scrim = Color.Black,
    )

    val typography = Typography(
        displayLarge = T.Display,
        headlineMedium = T.Headline,
        titleLarge = T.Title1,
        titleMedium = T.Title2,
        bodyLarge = T.Body1,
        bodyMedium = T.Body2,
        labelLarge = T.Label,
        labelSmall = T.Caption,
    )

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(D.RadiusTag),
        small = RoundedCornerShape(D.RadiusTag),
        medium = RoundedCornerShape(D.RadiusEpisode),
        large = RoundedCornerShape(D.RadiusPoster),
        extraLarge = RoundedCornerShape(D.RadiusField),
    )

    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
