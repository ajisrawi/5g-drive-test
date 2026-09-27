package com.drivetest.rf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.drivetest.rf.core.AppSettings

@Composable
fun DriveTestTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun DriveTestApp(onStart: () -> Unit, onStop: () -> Unit, onShare: (String) -> Unit) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(onDone = { showSettings = false })
    } else {
        val settings = remember { AppSettings.load(ctx) }
        MainScreen(settings, onStart, onStop, onSettings = { showSettings = true }, onShare = onShare)
    }
}
