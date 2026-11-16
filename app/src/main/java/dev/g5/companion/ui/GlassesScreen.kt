package dev.g5.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.g5.companion.BuildConfig
import dev.g5.companion.Glasses
import dev.g5.companion.MediaSync
import kotlinx.coroutines.launch

@Composable
fun GlassesScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val s by Glasses.state.collectAsStateWithLifecycle()
    val sync by MediaSync.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.name ?: "No glasses paired", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        s.connected -> "Connected"
                        s.connecting -> "Connecting…"
                        s.mac != null && s.autoReconnect -> "Disconnected · auto-reconnecting"
                        s.mac != null -> "Disconnected"
                        else -> "Scan to pair your DPVR G5"
                    },
                )
                if (s.connected) {
                    Text("Battery: ${s.battery?.let { "$it%" } ?: "–"}${if (s.charging) " (charging)" else ""}")
                    Text("New since last sync: ${s.mediaCount ?: "–"}")
                    s.model?.let { Text("Model: $it", style = MaterialTheme.typography.bodySmall) }
                }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                if (s.connected || s.connecting || (s.mac != null && s.autoReconnect)) {
                    OutlinedButton(
                        onClick = { Glasses.disconnect() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text("BT Disconnect")
                    }
                } else s.mac?.let { mac ->
                    Button(
                        onClick = { Glasses.connect(mac, s.name) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text("Connect")
                    }
                }
            }
        }

        if (!s.connected) DevicePicker(s) else {
            Text("Capture", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { Glasses.photo() } }) { Text("Photo") }
                Button(onClick = { scope.launch { if (s.recordingVideo) Glasses.stopVideo() else Glasses.startVideo() } }) {
                    Text(if (s.recordingVideo) "Stop video" else "Video")
                }
                Button(onClick = { scope.launch { if (s.recordingAudio) Glasses.stopAudio() else Glasses.startAudio() } }) {
                    Text(if (s.recordingAudio) "Stop audio" else "Audio")
                }
            }
            HorizontalDivider()
            Button(onClick = { MediaSync.start(ctx) }, enabled = !sync.running, modifier = Modifier.fillMaxWidth()) { Text("Sync All Media") }
        }

        if (sync.running || sync.stage.isNotEmpty()) {
            Text("${sync.stage}${if (sync.total > 0) " · ${sync.done}/${sync.total}" else ""} · ${sync.bytes / 1_048_576} MB")
            if (sync.running) {
                if (sync.total > 0) LinearProgressIndicator(progress = { sync.done / sync.total.toFloat() }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (sync.failed > 0) Text("${sync.failed} failed", color = MaterialTheme.colorScheme.error)
            sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
        Text(
            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun DevicePicker(s: Glasses.State) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = { if (s.scanning) Glasses.stopScan(ctx) else Glasses.startScan(ctx) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (s.scanning) "Stop scan" else "Scan for glasses")
    }
    s.found.forEach { d ->
        Row(
            Modifier.fillMaxWidth().clickable { Glasses.stopScan(ctx); Glasses.connect(d.mac, d.name) }.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(d.name)
                Text(d.mac, style = MaterialTheme.typography.bodySmall)
            }
            Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}
