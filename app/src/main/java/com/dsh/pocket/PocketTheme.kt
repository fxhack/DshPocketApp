package com.dsh.pocket

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 亮色配色：靛蓝主调，纯净背景，层次分明的表面色阶。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F6EF7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0B1F6B),
    secondary = Color(0xFF5C5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF6B4FAA),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF250058),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF4F6EF7),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF2F0F4),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

/** 暗色配色：深邃靛蓝主调，低饱和深色表面，护眼且层次清晰。 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB6C4FF),
    onPrimary = Color(0xFF122A78),
    primaryContainer = Color(0xFF2A43A5),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFC5C4DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFCFBCFF),
    onTertiary = Color(0xFF3B006E),
    tertiaryContainer = Color(0xFF541E94),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFB6C4FF),
    inverseSurface = Color(0xFFE4E2E6),
    inverseOnSurface = Color(0xFF2F3036),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/** 语义色扩展：消息气泡、代码块等，适配明暗模式。 */
data class PocketExtendedColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val assistantBubble: Color,
    val onAssistantBubble: Color,
    val codeBackground: Color,
    val onCode: Color,
    val toolChipBackground: Color,
    val onToolChip: Color,
)

private val LightExtended = PocketExtendedColors(
    userBubble = Color(0xFF4F6EF7),
    onUserBubble = Color.White,
    assistantBubble = Color(0xFFF0F0F5),
    onAssistantBubble = Color(0xFF1B1B1F),
    codeBackground = Color(0xFFF5F5F8),
    onCode = Color(0xFFD6336C),
    toolChipBackground = Color(0xFFEAEAF0),
    onToolChip = Color(0xFF46464F),
)

private val DarkExtended = PocketExtendedColors(
    userBubble = Color(0xFF2A43A5),
    onUserBubble = Color(0xFFDCE4FF),
    assistantBubble = Color(0xFF1E1F24),
    onAssistantBubble = Color(0xFFE4E2E6),
    codeBackground = Color(0xFF1A1B1F),
    onCode = Color(0xFFE9A0C8),
    toolChipBackground = Color(0xFF2A2B30),
    onToolChip = Color(0xFFC7C5D0),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** 圆角体系：small=8 / medium=12 / large=20，统一全应用圆角。 */
private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 主题模式：light / dark / system。
 *
 * @param themeMode Prefs.theme() 的值；system 跟随系统 isSystemInDarkTheme。
 */
@Composable
fun PocketTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val ext = if (darkTheme) DarkExtended else LightExtended
    CompositionLocalProvider(LocalExtendedColors provides ext) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes = PocketShapes,
            content = content
        )
    }
}
