package cn.yunji.journal.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Primary = Color(0xFF356B53)
val Soft = Color(0xFFE7F0E6)
val Background = Color(0xFFF7F8F3)
val Ink = Color(0xFF223C32)
val Muted = Color(0xFF5F7166)
val Outline = Color(0xFFDDE4DA)
/** Warm apricot accent from the design foundations; used by timeline note cards. */
val Apricot = Color(0xFFFAEAD8)
val Amber = Color(0xFF80582C)
val Danger = Color(0xFFA44238)
val DangerSoft = Color(0xFFFCECE7)

private val light = lightColorScheme(primary = Primary, onPrimary = Color.White, primaryContainer = Soft,
    secondary = Primary, onSecondary = Color.White, secondaryContainer = Soft, onSecondaryContainer = Primary,
    tertiary = Muted, onTertiary = Color.White, tertiaryContainer = Soft, onTertiaryContainer = Ink,
    onPrimaryContainer = Primary, background = Background, onBackground = Ink, surface = Color.White,
    onSurface = Ink, onSurfaceVariant = Muted, outline = Outline, outlineVariant = Outline, surfaceVariant = Soft,
    error = Danger, onError = Color.White, errorContainer = DangerSoft, onErrorContainer = Danger,
    // Material3 container roles added in 1.2+ were left at the default lavender-grey palette; map every one of
    // them back to the warm green/white design palette so dialogs, menus, switches and snackbars stay on-brand.
    surfaceTint = Primary,
    surfaceBright = Color.White,
    surfaceDim = Outline,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Outline,
    inverseSurface = Ink, inverseOnSurface = Background, inversePrimary = Soft)
private val dark = darkColorScheme(primary = Color(0xFFA5D0B3), onPrimary = Color(0xFF173D2A),
    secondary = Color(0xFFA5D0B3), onSecondary = Color(0xFF173D2A),
    secondaryContainer = Color(0xFF294C39), onSecondaryContainer = Color(0xFFD5EBD8),
    tertiary = Color(0xFFB9C8BB), onTertiary = Color(0xFF173D2A),
    tertiaryContainer = Color(0xFF294C39), onTertiaryContainer = Color(0xFFD5EBD8),
    primaryContainer = Color(0xFF294C39), onPrimaryContainer = Color(0xFFD5EBD8),
    background = Color(0xFF141D17), surface = Color(0xFF1D2921), onBackground = Color(0xFFE3EADF),
    onSurface = Color(0xFFE3EADF), onSurfaceVariant = Color(0xFFB9C8BB),
    outline = Color(0xFF8A9D8D), outlineVariant = Color(0xFF405647),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    surfaceTint = Color(0xFFA5D0B3), surfaceBright = Color(0xFF34443A), surfaceDim = Color(0xFF101711),
    surfaceContainerLowest = Color(0xFF101711), surfaceContainerLow = Color(0xFF17231B),
    surfaceContainer = Color(0xFF1B2A20), surfaceContainerHigh = Color(0xFF213228),
    surfaceContainerHighest = Color(0xFF294C39),
    inverseSurface = Color(0xFFE3EADF), inverseOnSurface = Color(0xFF173D2A), inversePrimary = Primary)
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight)
private val typography = Typography(displaySmall = type(40, 60, FontWeight.Bold),
    headlineMedium = type(28, 42, FontWeight.Bold), titleLarge = type(20, 30, FontWeight.Medium),
    bodyLarge = type(16, 24), bodyMedium = type(14, 21), labelLarge = type(16, 24, FontWeight.Medium),
    labelMedium = type(14, 21, FontWeight.Medium), bodySmall = type(12, 18), labelSmall = type(12, 18))

@Composable
fun PregnancyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = typography, content = content)
}
