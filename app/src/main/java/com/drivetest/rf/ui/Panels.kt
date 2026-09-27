package com.drivetest.rf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.drivetest.rf.core.CallResult
import com.drivetest.rf.core.DriveTestState
import com.drivetest.rf.core.Kpi
import com.drivetest.rf.core.RadioSnapshot
import com.drivetest.rf.core.fmt

@Composable
fun CellsPanel(r: RadioSnapshot?) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        if (r == null) {
            item { Text("No radio data yet.") }
            return@LazyColumn
        }
        item {
            Text("Serving", style = MaterialTheme.typography.titleSmall)
            r.nr?.let { n ->
                Mono("NR  reg=${n.registered} NCI=${n.nci.d()} PCI=${n.pci.d()} TAC=${n.tac.d()} ARFCN=${n.arfcn.d()} ${n.bands ?: ""}")
                Mono("    SS-RSRP=${n.ssRsrp.d()} SS-RSRQ=${n.ssRsrq.d()} SS-SINR=${n.ssSinr.d()}")
                Mono("    CSI-RSRP=${n.csiRsrp.d()} CSI-RSRQ=${n.csiRsrq.d()} CSI-SINR=${n.csiSinr.d()}")
            }
            r.lte?.let { l ->
                Mono("LTE reg=${l.registered} ECI=${l.eci.d()} PCI=${l.pci.d()} TAC=${l.tac.d()} EARFCN=${l.earfcn.d()} ${l.bands ?: ""} BW=${l.bandwidthKhz.d()}kHz")
                Mono("    RSRP=${l.rsrp.d()} RSRQ=${l.rsrq.d()} SINR=${l.sinr.d()} RSSI=${l.rssi.d()} CQI=${l.cqi.d()} TA=${l.ta.d()}")
            }
            r.wcdma?.let { w -> Mono("WCDMA CID=${w.cid.d()} LAC=${w.lac.d()} UARFCN=${w.uarfcn.d()} PSC=${w.psc.d()} RSCP=${w.rscp.d()} EcNo=${w.ecno.d()}") }
            r.gsm?.let { g -> Mono("GSM CID=${g.cid.d()} LAC=${g.lac.d()} ARFCN=${g.arfcn.d()} BSIC=${g.bsic.d()} RSSI=${g.rssi.d()} BER=${g.ber.d()}") }
            Spacer(Modifier.padding(4.dp))
            Text("Neighbours (${r.neighbors.size})", style = MaterialTheme.typography.titleSmall)
            Mono("RAT    ARFCN   PCI/PSC  LEVEL  QUAL")
            HorizontalDivider()
        }
        items(r.neighbors.sortedByDescending { it.level ?: Int.MIN_VALUE }) { n ->
            Mono("%-6s %-7s %-8s %-6s %s".format(n.rat, n.arfcn.d(), n.id.d(), n.level.d(), n.quality.d()),
                color = Color(Kpi.level(n.level)))
        }
    }
}

@Composable
private fun Mono(text: String, color: Color = Color.Unspecified) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun CallsPanel() {
    val calls by DriveTestState.calls.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        item { CallSummary(calls) }
        items(calls.reversed(), key = { it.id }) { CallCard(it) }
    }
}

@Composable
private fun CallSummary(calls: List<CallResult>) {
    val final = calls.filter { it.final }
    val ok = final.count { it.status == "OK" }
    val dropped = final.count { it.status == "DROPPED" }
    val failed = final.count { it.failed && it.status != "DROPPED" }
    val mos = final.mapNotNull { it.mos }
    val setup = final.mapNotNull { it.setupSec }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("Voice KPIs", style = MaterialTheme.typography.titleSmall)
            Text("Attempts ${calls.size} · OK $ok · Dropped $dropped · Setup fail $failed")
            if (final.isNotEmpty()) {
                val cssr = (final.size - failed) * 100.0 / final.size
                val dcr = if (final.size - failed > 0) dropped * 100.0 / (final.size - failed) else 0.0
                Text("CSSR ${cssr.fmt(1)} % · DCR ${dcr.fmt(1)} %")
            }
            if (mos.isNotEmpty()) Text("Avg MOS ${mos.average().fmt(2)} (min ${mos.min().fmt(1)})")
            if (setup.isNotEmpty()) Text("Avg setup ${setup.average().fmt(1)} s (approx.)")
        }
    }
}

@Composable
private fun CallCard(c: CallResult) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${c.id}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                val color = when {
                    !c.final -> Kpi.IN_CALL
                    c.failed -> Kpi.BAD
                    else -> Kpi.mos(c.mos)
                }
                Text(
                    c.status, color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.background(Color(color), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                c.mos?.let { Text("MOS ${it.fmt(1)}", fontWeight = FontWeight.Bold) }
            }
            val details = listOfNotNull(
                c.place,
                c.techs,
                c.setupSec?.let { "setup ${it.fmt(1)} s" },
                c.recordSec?.let { "recorded ${it} s" },
                c.avgLevel?.let { "avg ${it.fmt(0)} / min ${c.minLevel.d()} dBm" },
                c.avgSinr?.let { "SINR ${it.fmt(1)} dB" },
                c.audio?.let { "SNR ${it.snrDb.fmt(0)} dB · dropouts ${it.dropouts}" },
                c.choppiness?.let { "choppy: $it" },
                c.noise?.let { "noise: $it" },
            ).joinToString(" · ")
            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall)
            c.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            c.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun LogPanel() {
    val events by DriveTestState.events.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        items(events) { Mono(it) }
    }
}
