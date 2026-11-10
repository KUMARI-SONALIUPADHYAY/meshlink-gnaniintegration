package com.meshlink.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Premium light interface: deep navy anchors, clear blue actions and teal connectivity.
private val MeshScheme = lightColorScheme(
    primary = Color(0xFF155E9A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFA),
    onPrimaryContainer = Color(0xFF0B2F52),

    secondary = Color(0xFF087F8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4F2F1),
    onSecondaryContainer = Color(0xFF073F45),

    tertiary = Color(0xFF5267B5),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E9FF),
    onTertiaryContainer = Color(0xFF202D66),

    background = Color(0xFFF3F6FA),
    onBackground = Color(0xFF152337),

    surface = Color.White,
    onSurface = Color(0xFF152337),

    surfaceVariant = Color(0xFFEAF0F6),
    onSurfaceVariant = Color(0xFF526477),
    outline = Color(0xFFB9C8D7),
    outlineVariant = Color(0xFFD9E2EC),

    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE8EEF5),
    surfaceContainerHighest = Color(0xFFDDE7F0),

    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E2),
    onErrorContainer = Color(0xFF641B16)
)

@Composable
fun MeshLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshScheme,
        typography = Typography,
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
        ),
        content = content
    )
}
