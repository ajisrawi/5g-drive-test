package com.drivetest.rf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.drivetest.rf.core.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(AppSettings.load(ctx)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Section("Session")
            IntField("Duration (minutes, 0 = until stopped)", s.sessionMinutes) { s = s.copy(sessionMinutes = it) }
            IntField("Sample interval (ms)", s.sampleIntervalMs) { s = s.copy(sampleIntervalMs = it) }

            Section("Voice test (Twilio)")
            SwitchField("Enable voice calls", s.callsEnabled) { s = s.copy(callsEnabled = it) }
            TextField("Twilio number to call (E.164, e.g. +15551234567)", s.twilioNumber, KeyboardType.Phone) { s = s.copy(twilioNumber = it) }
            TextField("Twilio Account SID", s.twilioAccountSid) { s = s.copy(twilioAccountSid = it) }
            TextField("Twilio Auth Token", s.twilioAuthToken, secret = true) { s = s.copy(twilioAuthToken = it) }
            IntField("Pause between calls (s)", s.callIntervalSec) { s = s.copy(callIntervalSec = it) }
            IntField("Record length (s) – must equal maxLength in TwiML", s.recordSec) { s = s.copy(recordSec = it) }
            SwitchField("Play test sentences on speaker during call (experimental)", s.speechStimulus) { s = s.copy(speechStimulus = it) }
            SwitchField("Delete Twilio recordings after analysis", s.deleteRecordings) { s = s.copy(deleteRecordings = it) }

            Section("AI call-quality scoring (OpenRouter)")
            TextField("OpenRouter API key", s.openRouterKey, secret = true) { s = s.copy(openRouterKey = it) }
            TextField("Model (must accept audio input)", s.openRouterModel) { s = s.copy(openRouterModel = it) }
            Text(
                "Without a key the app uses a heuristic MOS from SNR, dropouts and clipping.",
                style = MaterialTheme.typography.bodySmall,
            )

            Section("Video test (YouTube)")
            SwitchField("Enable YouTube video test", s.videoEnabled) { s = s.copy(videoEnabled = it) }
            TextField("YouTube video URL or ID", s.youtubeUrl, KeyboardType.Uri) { s = s.copy(youtubeUrl = it) }
            SwitchField("Use desktop watch page instead of embed player", s.youtubeWatchPage) { s = s.copy(youtubeWatchPage = it) }

            Button(
                onClick = { s.save(ctx); onDone() },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun TextField(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchField(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
