package dev.alicebridge.app.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AliceBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AbColors.Purple,
            background = AbColors.Ink,
            surface = AbColors.Panel,
            onPrimary = AbColors.Ink,
            onBackground = AbColors.Text,
            onSurface = AbColors.Text,
            outline = AbColors.Outline,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = AbColors.Ink, content = content)
    }
}
