package com.drivetest.rf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drivetest.rf.core.AppSettings
import com.drivetest.rf.core.DriveTestState
import com.drivetest.rf.core.Kpi
import com.drivetest.rf.core.RadioSnapshot
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class Panel(val title: String) { MAP("Map"), CELLS("Cells"), CALLS("Voice"), LOG("Log") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: AppSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onShare: (String) -> Unit,
) {
    val running by DriveTestState.running.collectAsState()
    val radio by DriveTestState.radio.collectAsState()
    val dir by DriveTestState.sessionDir.collectAsState()
    var panel by rememberSaveable { mutableStateOf(Panel.MAP) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("5G Drive Test") },
                actions = {
                    IconButton(onClick = { dir?.let(onShare) }, enabled = dir != null) {
                        Icon(Icons.Default.Share, contentDescription = "Share session files")
                    }
                    IconButton(onClick = onSettings, enabled = !running) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SessionBar(running, settings, onStart, onStop)
            RadioCard(radio)
            TabRow(selectedTabIndex = panel.ordinal) {
                Panel.entries.forEach { p ->
                    Tab(selected = panel == p, onClick = { panel = p }, text = { Text(p.title) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (panel) {
                    Panel.MAP -> MapPanel(Modifier.fillMaxSize())
                    Panel.CELLS -> CellsPanel(radio)
                    Panel.CALLS -> CallsPanel()
                    Panel.LOG -> LogPanel()
                }
            }
            if (running && settings.videoEnabled) VideoPanel(settings)
        }
    }
}

@Composable
private fun SessionBar(running: Boolean, settings: AppSettings, onStart: () -> Unit, onStop: () -> Unit) {
    val start by DriveTestState.sessionStart.collectAsState()
    val samples by DriveTestState.sampleCount.collectAsState()
    val loc by DriveTestState.location.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = if (running) onStop else onStart,
            colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
        ) { Text(if (running) "STOP" else "START") }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val planned = if (settings.sessionMinutes > 0) " / ${formatDuration(settings.sessionMinutes * 60_000L)}" else " / ∞"
            Text(
                (if (running) formatDuration(now - start) else "Idle") + planned,
                style = MaterialTheme.typography.titleMedium,
            )
            val gps = loc?.let { "GPS ±${it.accuracy.roundToInt()} m · ${(it.speed * 3.6).roundToInt()} km/h" } ?: "No GPS fix"
            Text("$samples samples · $gps", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RadioCard(r: RadioSnapshot?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (r == null) {
                Text("Press START to read serving cell, GPS, voice and video KPIs.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.tech,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(Kpi.tech(r.tech)), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${r.operator} (${r.mccMnc}) · ${r.dataNetwork}/${r.overrideNetwork}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            r.nr?.let { n ->
                KpiRow(
                    "NR ${n.bands ?: ""}  PCI ${n.pci.d()}  ARFCN ${n.arfcn.d()}",
                    listOf(
                        Triple("SS-RSRP", n.ssRsrp, Kpi.level(n.ssRsrp)),
                        Triple("SS-RSRQ", n.ssRsrq, null),
                        Triple("SS-SINR", n.ssSinr, Kpi.sinr(n.ssSinr)),
                    ),
                )
            }
            r.lte?.let { l ->
                KpiRow(
                    "LTE ${l.bands ?: ""}  PCI ${l.pci.d()}  EARFCN ${l.earfcn.d()}  ECI ${l.eci.d()}",
                    listOf(
                        Triple("RSRP", l.rsrp, Kpi.level(l.rsrp)),
                        Triple("RSRQ", l.rsrq, null),
                        Triple("SINR", l.sinr, Kpi.sinr(l.sinr)),
                    ),
                )
            }
            r.wcdma?.let { w ->
                KpiRow("WCDMA PSC ${w.psc.d()}  UARFCN ${w.uarfcn.d()}",
                    listOf(Triple("RSCP", w.rscp, Kpi.level(w.rscp)), Triple("Ec/No", w.ecno, null)))
            }
            r.gsm?.let { g ->
                KpiRow("GSM BSIC ${g.bsic.d()}  ARFCN ${g.arfcn.d()}",
                    listOf(Triple("RSSI", g.rssi, Kpi.level(g.rssi)), Triple("BER", g.ber, null)))
            }
        }
    }
}

@Composable
private fun KpiRow(title: String, values: List<Triple<String, Int?, Int?>>) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            values.forEach { (name, v, color) ->
                Column {
                    Text(name, style = MaterialTheme.typography.labelSmall)
                    Text(
                        v.d(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = color?.let { Color(it) } ?: LocalContentColor.current,
                    )
                }
            }
        }
    }
}

internal fun Int?.d(): String = this?.toString() ?: "–"
internal fun Long?.d(): String = this?.toString() ?: "–"

internal fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}
