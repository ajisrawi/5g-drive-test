package com.drivetest.rf.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.drivetest.rf.core.CallResult
import com.drivetest.rf.core.DriveTestState
import com.drivetest.rf.core.Kpi
import com.drivetest.rf.core.MapLayer
import com.drivetest.rf.core.MapRuns
import com.drivetest.rf.core.fmt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

@Composable
fun MapPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val points by DriveTestState.points.collectAsState()
    val calls by DriveTestState.calls.collectAsState()
    val loc by DriveTestState.location.collectAsState()
    var layer by rememberSaveable { mutableStateOf(MapLayer.LEVEL) }
    var follow by rememberSaveable { mutableStateOf(true) }
    val runs = remember(points, layer) { MapRuns.build(points, layer) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f) }
    val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    LaunchedEffect(loc, follow) {
        val l = loc ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val zoom = if (camera.position.zoom < 13f) 16f else camera.position.zoom
        runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude, l.longitude), zoom), 600) }
    }
    LaunchedEffect(camera.isMoving) {
        if (camera.isMoving && camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) follow = false
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = hasLocation),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false, mapToolbarEnabled = false),
        ) {
            runs.forEach { r -> Polyline(points = r.points, color = Color(r.color), width = 14f) }
            if (layer == MapLayer.CALLS) {
                calls.forEach { c ->
                    val lat = c.lat
                    val lon = c.lon
                    if (lat != null && lon != null) key(c.id) {
                        Marker(
                            state = rememberMarkerState(key = "call${c.id}", position = LatLng(lat, lon)),
                            title = "Call #${c.id} · ${c.status}",
                            snippet = callSnippet(c),
                            icon = BitmapDescriptorFactory.defaultMarker(Kpi.markerHue(c)),
                        )
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopStart).horizontalScroll(rememberScrollState()).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapLayer.entries.forEach { l ->
                FilterChip(
                    selected = layer == l,
                    onClick = { layer = l },
                    label = { Text(l.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ),
                )
            }
        }
        Legend(layer, Modifier.align(Alignment.BottomStart).padding(8.dp))
        if (!follow) {
            SmallFloatingActionButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) { Icon(Icons.Default.LocationOn, contentDescription = "Follow my position") }
        }
    }
}

@Composable
private fun Legend(layer: MapLayer, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Kpi.legend(layer).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(color), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun callSnippet(c: CallResult) = buildString {
    c.mos?.let { append("MOS ${it.fmt(1)} · ") }
    c.setupSec?.let { append("setup ${it.fmt(1)} s · ") }
    c.avgLevel?.let { append("avg ${it.fmt(0)} dBm · ") }
    c.techs?.let { append(it) }
}
