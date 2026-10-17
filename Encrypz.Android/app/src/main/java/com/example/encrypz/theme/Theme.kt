package com.example.encrypz.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EncrypzColorScheme = darkColorScheme(
    primary = EncrypzBlue,
    onPrimary = EncrypzText,
    primaryContainer = Color(0xFF172B4D),
    onPrimaryContainer = EncrypzBlueSoft,
    secondary = EncrypzBlueBright,
    onSecondary = EncrypzBlack,
    secondaryContainer = EncrypzSecondaryBg,
    onSecondaryContainer = EncrypzBlueBright,
    tertiary = EncrypzEmeraldBright,
    onTertiary = EncrypzBlack,
    background = EncrypzBlack,
    onBackground = EncrypzText,
    surface = EncrypzSurface,
    onSurface = EncrypzText,
    surfaceVariant = EncrypzPanel,
    onSurfaceVariant = EncrypzMuted,
    outline = EncrypzBorder,
    outlineVariant = EncrypzInputBorder,
    error = EncrypzDanger,
    onError = EncrypzText,
    errorContainer = EncrypzDangerBg,
    onErrorContainer = EncrypzDangerLight
)

@Composable
fun EncrypzTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = EncrypzColorScheme,
        typography = Typography,
        content = content
    )
}
