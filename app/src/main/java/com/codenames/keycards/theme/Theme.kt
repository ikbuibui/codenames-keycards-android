package com.codenames.keycards.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KeycardsDarkColors =
  darkColorScheme(
    primary = Color(0xFF59C1B6),
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFFA6F3EA),
    secondary = Color(0xFFB3CCC7),
    background = Color(0xFF181818),
    onBackground = Color(0xFFF5F0EC),
    surface = Color(0xFF282828),
    onSurface = Color(0xFFF5F0EC),
    surfaceVariant = Color(0xFF383838),
    onSurfaceVariant = Color(0xFFD5C3BD),
    outline = Color(0xFF9E8C86),
  )

/** A fixed in-app palette keeps the keycard readable on every device. */
@Composable
fun CodenamesKeycardsTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = KeycardsDarkColors, content = content)
}
