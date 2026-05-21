package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ElectricIndigo,
    secondary = BrightCyan,
    tertiary = NebulaPink,
    background = MidnightSlate,
    surface = CardSlate,
    onPrimary = GhostWhite,
    onSecondary = MidnightSlate,
    onBackground = GhostWhite,
    onSurface = GhostWhite
  )

private val LightColorScheme =
  darkColorScheme( // We default to dark color scheme because user requested a highly modern premium sleek dark interface!
    primary = ElectricIndigo,
    secondary = BrightCyan,
    tertiary = NebulaPink,
    background = MidnightSlate,
    surface = CardSlate,
    onPrimary = GhostWhite,
    onSecondary = MidnightSlate,
    onBackground = GhostWhite,
    onSurface = GhostWhite
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is disabled by default to keep the custom pure pitch dark theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
