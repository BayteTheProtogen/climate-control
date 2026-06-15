package com.jacobp.szklarnia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = NavyAccent,
    secondary = GreenActive,
    tertiary = OrangeWarning,
    background = BackgroundGray,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = CharcoalText,
    onBackground = CharcoalText,
    onSurface = CharcoalText
)

@Composable
fun SzklarniaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
