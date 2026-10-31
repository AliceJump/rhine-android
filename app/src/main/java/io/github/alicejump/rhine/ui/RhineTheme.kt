package io.github.alicejump.rhine.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF1F0E9)
private val Ink = Color(0xFF252821)
val ArchiveOrange = Color(0xFFE57A38)

@Composable
fun RhineTheme(dark: Boolean, accentIndex: Int, content: @Composable () -> Unit) {
    val accent = listOf(ArchiveOrange, Color(0xFF5B8F85), Color(0xFF557BA4))[accentIndex.coerceIn(0, 2)]
    val colors = if (dark) darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF171A16),
        secondary = Color(0xFFCBD0C2),
        background = Color(0xFF1D211D),
        onBackground = Paper,
        surface = Color(0xFF1D211D),
        onSurface = Paper,
        surfaceVariant = Color(0xFF2C312B),
        onSurfaceVariant = Color(0xFFBCC1B6),
        outline = Color(0xFF6D7367),
    ) else lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = Ink,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = Color(0xFFE5E6DD),
        onSurfaceVariant = Color(0xFF6C7265),
        outline = Color(0xFF979D90),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
