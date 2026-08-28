package com.burikktv.iptv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val TvBackground = Color(0xFF0B0F14)
val TvSurface = Color(0xFF161C24)
val TvSurfaceVariant = Color(0xFF232B36)
val TvPrimary = Color(0xFF3DDC84)
val TvOnPrimary = Color(0xFF04140B)
val TvTextPrimary = Color(0xFFF2F5F7)
val TvTextSecondary = Color(0xFF9AA5B1)

private val BurikkColorScheme = darkColorScheme(
    primary = TvPrimary,
    onPrimary = TvOnPrimary,
    background = TvBackground,
    onBackground = TvTextPrimary,
    surface = TvSurface,
    onSurface = TvTextPrimary,
    surfaceVariant = TvSurfaceVariant,
    onSurfaceVariant = TvTextSecondary,
)

@Composable
fun BurikkTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BurikkColorScheme,
        content = content,
    )
}
