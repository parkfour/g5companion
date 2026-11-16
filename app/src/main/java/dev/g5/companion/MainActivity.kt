package dev.g5.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.g5.companion.ui.AiScreen
import dev.g5.companion.ui.GalleryScreen
import dev.g5.companion.ui.GlassesScreen

class MainActivity : ComponentActivity() {
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startServiceIfAllowed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startServiceIfAllowed() else request.launch(missing.toTypedArray())
        ThemeState.mode = Prefs(this).theme
        setContent { AppTheme { Root() } }
    }

    /** connectedDevice FGS requires BLUETOOTH_CONNECT at runtime. */
    private fun startServiceIfAllowed() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) GlassesService.start(this)
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    GLASSES("Glasses", Icons.Filled.Home),
    GALLERY("Gallery", Icons.Filled.Face),
    AI("AI & Settings", Icons.Filled.Settings),
}

@androidx.compose.runtime.Composable
private fun Root() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (Tab.entries[tab]) {
            Tab.GLASSES -> GlassesScreen(m)
            Tab.GALLERY -> GalleryScreen(m)
            Tab.AI -> AiScreen(m)
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = when (ThemeState.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}
