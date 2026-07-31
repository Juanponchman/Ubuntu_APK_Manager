package cn.termux.ubuntumanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2ED),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF4A6361),
    secondaryContainer = Color(0xFFCCE8E5),
    tertiary = Color(0xFF49607A),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF4FBF9),
    surface = Color(0xFFF4FBF9),
    surfaceVariant = Color(0xFFDAE5E3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D0),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFF9CF2ED),
    secondary = Color(0xFFB0CCC9),
    secondaryContainer = Color(0xFF324B49),
    tertiary = Color(0xFFB1C8E7),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1D252A),
)

@Composable
fun UbuntuManagerTheme(
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

