/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mobileharness.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE9ECEA),
    onPrimary = Color(0xFF171B19),
    primaryContainer = Color(0xFF303C36),
    onPrimaryContainer = Color(0xFFE4ECE6),
    secondary = Color(0xFFB7C8BD),
    secondaryContainer = Color(0xFF303A34),
    onSecondaryContainer = Color(0xFFE2EBE4),
    tertiary = Color(0xFFAFC8BC),
    background = Color(0xFF151715),
    onBackground = Color(0xFFE8EAE7),
    surface = Color(0xFF151715),
    onSurface = Color(0xFFE8EAE7),
    surfaceContainerLowest = Color(0xFF101210),
    surfaceContainerLow = Color(0xFF202420),
    surfaceContainer = Color(0xFF262A26),
    surfaceContainerHigh = Color(0xFF2B302B),
    surfaceContainerHighest = Color(0xFF333833),
    onSurfaceVariant = Color(0xFFB8BFB8),
    outline = Color(0xFF7A857C),
    outlineVariant = Color(0xFF3B453D),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF246FA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EBFF),
    onPrimaryContainer = Color(0xFF08263D),
    secondary = Color(0xFF246FA8),
    secondaryContainer = Color(0xFFE6F3FF),
    onSecondaryContainer = Color(0xFF15384F),
    tertiary = Color(0xFF377FAE),
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4FAFF),
    surfaceContainer = Color(0xFFEBF5FF),
    surfaceContainerHigh = Color(0xFFE3F1FE),
    surfaceContainerHighest = Color(0xFFDCEEFF),
    onSurfaceVariant = Color(0xFF434A50),
    outline = Color(0xFF7692A8),
    outlineVariant = Color(0xFFBCD8EC),
)

@Composable
fun MobileHarnessTheme(
    darkTheme: Boolean = false,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

