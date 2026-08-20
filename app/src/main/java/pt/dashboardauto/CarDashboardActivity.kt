package pt.dashboardauto

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** Full-screen driving dashboard. It never uses an overlay or system split-screen. */
class CarDashboardActivity : ComponentActivity() {
    private var dashboardMapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { CarDashboardScreen() }
    }

    override fun onStart() {
        super.onStart()
        dashboardMapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        dashboardMapView?.onResume()
    }

    override fun onPause() {
        dashboardMapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        dashboardMapView?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        dashboardMapView?.onDestroy()
        dashboardMapView = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        dashboardMapView?.onLowMemory()
        super.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        dashboardMapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun CarDashboardScreen() {
        val context = LocalContext.current
        var track by remember { mutableStateOf("Sem música ativa") }
        var artist by remember { mutableStateOf("") }
        var playing by remember { mutableStateOf(false) }
        var navigationFraction by remember { mutableStateOf(0.76f) }
        val navigationPackage = remember {
            getSharedPreferences("dashboard_auto", MODE_PRIVATE).getString("navigation_app", "").orEmpty()
        }

        LaunchedEffect(Unit) {
            while (true) {
                val parts = MusicController.currentTrack(context).split("\\n", limit = 2)
                track = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Sem música ativa"
                artist = parts.getOrNull(1).orEmpty()
                playing = MusicController.playbackInfo(context).playing
                delay(700L)
            }
        }

        val portrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        MaterialTheme {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0B0D12)).padding(10.dp)
            ) {
                val density = LocalDensity.current
                val availableHeightPx = with(density) { maxHeight.toPx() }
                val availableWidthPx = with(density) { maxWidth.toPx() }
                if (portrait) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapPane(
                            modifier = Modifier.weight(navigationFraction).fillMaxWidth(),
                            navigationPackage = navigationPackage,
                            onClose = { finish() }
                        )
                        SplitDivider(vertical = false) { delta ->
                            navigationFraction = (navigationFraction + delta / availableHeightPx).coerceIn(0.25f, 0.82f)
                        }
                        PlayerPane(
                            modifier = Modifier.weight(1f - navigationFraction).fillMaxWidth(),
                            track = track,
                            artist = artist,
                            playing = playing,
                            onPlayPause = { MusicController.playPause(context) },
                            onPrevious = { MusicController.previous(context) },
                            onNext = { MusicController.next(context) }
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapPane(
                            modifier = Modifier.weight(navigationFraction).fillMaxHeight(),
                            navigationPackage = navigationPackage,
                            onClose = { finish() }
                        )
                        SplitDivider(vertical = true) { delta ->
                            navigationFraction = (navigationFraction + delta / availableWidthPx).coerceIn(0.55f, 0.84f)
                        }
                        PlayerPane(
                            modifier = Modifier.weight(1f - navigationFraction).fillMaxHeight(),
                            track = track,
                            artist = artist,
                            playing = playing,
                            onPlayPause = { MusicController.playPause(context) },
                            onPrevious = { MusicController.previous(context) },
                            onNext = { MusicController.next(context) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SplitDivider(vertical: Boolean, onDelta: (Float) -> Unit) {
        Box(
            modifier = Modifier
                .then(if (vertical) Modifier.width(12.dp).fillMaxHeight() else Modifier.height(12.dp).fillMaxWidth())
                .pointerInput(vertical) {
                    detectDragGestures { _, dragAmount ->
                        onDelta(if (vertical) dragAmount.x else dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = if (vertical) Modifier.width(4.dp).fillMaxHeight(.35f) else Modifier.height(4.dp).fillMaxWidth(.35f)
                    .background(Color(0xFF687080), RoundedCornerShape(4.dp))
            )
        }
    }

    @Composable
    private fun MapPane(modifier: Modifier, navigationPackage: String, onClose: () -> Unit) {
        val context = LocalContext.current
        val mapView = remember { MapView(context) }
        Card(modifier = modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121722))) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = {
                        dashboardMapView = mapView
                        mapView.onCreate(null)
                        mapView.getMapAsync { map -> configureMap(map) }
                        mapView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xCC10151F))) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Navigation, null, tint = Color(0xFF8CE6C0))
                            Spacer(Modifier.width(8.dp))
                            Text("Navegação DriveDeck", color = Color.White)
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, "Fechar dashboard", tint = Color.White)
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Mapa OpenFreeMap", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text("OpenFreeMap © OpenMapTiles · dados OSM", color = Color(0xFFD0D2D8), style = MaterialTheme.typography.labelSmall)
                    if (navigationPackage.isNotBlank()) {
                        Button(
                            onClick = {
                                context.packageManager.getLaunchIntentForPackage(navigationPackage)?.let { intent ->
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC182333))
                        ) { Text("Abrir navegação externa") }
                    }
                }
            }
        }
    }

    private fun configureMap(map: MapLibreMap) {
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isRotateGesturesEnabled = false
        map.cameraPosition = CameraPosition.Builder().target(LatLng(38.7223, -9.1393)).zoom(11.0).build()
        map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty"))
    }

    @Composable
    private fun PlayerPane(
        modifier: Modifier,
        track: String,
        artist: String,
        playing: Boolean,
        onPlayPause: () -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit
    ) {
        Card(modifier = modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFF8CE6C0), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Música", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(track, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    Text(artist, color = Color(0xFFB5BAC5), maxLines = 1)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onPlayPause, modifier = Modifier.align(Alignment.CenterHorizontally).size(64.dp)) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Reproduzir ou pausar", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Faixa anterior", tint = Color.White) }
                        IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Faixa seguinte", tint = Color.White) }
                    }
                }
            }
        }
    }
}
