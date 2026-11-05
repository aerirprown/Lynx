package app.lynx.prm.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import app.lynx.prm.match.foldCase
import kotlin.math.abs

val LynxCanvas = Color(0xFF16191D)
val LynxRaised = Color(0xFF1E2227)
val LynxRaisedMore = Color(0xFF23272E)
val LynxSelected = Color(0xFF2C313A)
val LynxField = Color(0xFF1D1F23)
val LynxHairline = Color(0xFF181A1F)
val LynxStroke = Color(0xFF3E4452)
val LynxText = Color(0xFFABB2BF)
val LynxTextStrong = Color(0xFFD7DAE0)
val LynxTextMuted = Color(0xFF9DA5B4)
val LynxPlaceholder = Color(0xFF6B717D)
val LynxButton = Color(0xFF404754)
val LynxButtonSecondary = Color(0xFF30333D)
val LynxButtonSecondaryText = Color(0xFFC0BDBD)
val LynxBlue = Color(0xFF61AFEF)
val LynxGreen = Color(0xFF98C379)
val LynxPurple = Color(0xFFC678DD)
val LynxYellow = Color(0xFFE5C07B)
val LynxOrange = Color(0xFFD19A66)
val LynxCyan = Color(0xFF56B6C2)
val LynxRed = Color(0xFFE06C75)

private val tagColors = listOf(LynxBlue, LynxGreen, LynxPurple, LynxYellow, LynxOrange, LynxCyan)

fun lynxColorScheme() = darkColorScheme(
    primary = LynxButton,
    onPrimary = LynxTextStrong,
    primaryContainer = LynxRaisedMore,
    onPrimaryContainer = LynxTextStrong,
    secondary = LynxButtonSecondary,
    onSecondary = LynxButtonSecondaryText,
    secondaryContainer = LynxRaised,
    onSecondaryContainer = LynxText,
    background = LynxCanvas,
    onBackground = LynxText,
    surface = LynxCanvas,
    onSurface = LynxText,
    surfaceVariant = LynxRaised,
    onSurfaceVariant = LynxTextMuted,
    surfaceContainer = LynxRaised,
    surfaceContainerHigh = LynxRaisedMore,
    surfaceContainerHighest = LynxSelected,
    outline = LynxStroke,
    outlineVariant = LynxHairline,
    error = LynxRed,
    onError = LynxTextStrong,
    errorContainer = LynxRaised,
    onErrorContainer = LynxRed,
)

fun tagColor(name: String): Color {
    val hash = abs(name.trim().foldCase().hashCode())
    return tagColors[hash % tagColors.size]
}

fun tagChipFill(name: String): Color {
    val color = tagColor(name)
    return Color(
        red = LynxCanvas.red * 0.8f + color.red * 0.2f,
        green = LynxCanvas.green * 0.8f + color.green * 0.2f,
        blue = LynxCanvas.blue * 0.8f + color.blue * 0.2f,
    )
}
