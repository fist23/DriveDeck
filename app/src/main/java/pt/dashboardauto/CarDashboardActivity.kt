package pt.dashboardauto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import android.location.Geocoder
import androidx.compose.ui.text.input.KeyboardType
import java.net.HttpURLConnection
import java.net.URL

/** Full-screen driving dashboard. It never uses an overlay or system split-screen. */
class CarDashboardActivity : ComponentActivity() {
    private var dashboardMapView: MapView? = null
    private var dashboardMap: MapLibreMap? = null
    private var vehicleMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var lastLocation: Location? = null
    private var followVehicle = true
    private var activeDestination: LatLng? = null
    private var activeRoutePoints: List<LatLng> = emptyList()
    private var activeSteps: List<RouteStep> = emptyList()
    private var activeStepIndex = 0
    private var routeRequestInProgress = false
    private var routeStatusListener: ((String) -> Unit)? = null
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "A localização é necessária para mostrar a posição no mapa.", Toast.LENGTH_LONG).show()
        }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateVehicleLocation(location)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { CarDashboardScreen() }
        ensureLocationPermission()
    }

    override fun onStart() {
        super.onStart()
        dashboardMapView?.onStart()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
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
        stopLocationUpdates()
        dashboardMapView?.onDestroy()
        dashboardMapView = null
        dashboardMap = null
        vehicleMarker = null
        routePolyline = null
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

    private fun ensureLocationPermission() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) startLocationUpdates()
        else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter(locationManager::isProviderEnabled)
                .forEach { provider ->
                    locationManager.requestLocationUpdates(provider, 2000L, 5f, locationListener, mainLooper)
                    locationManager.getLastKnownLocation(provider)?.let(::updateVehicleLocation)
                }
        } catch (_: SecurityException) { }
    }

    private fun stopLocationUpdates() {
        try { locationManager.removeUpdates(locationListener) } catch (_: SecurityException) { }
    }

    private fun updateVehicleLocation(location: Location) {
        lastLocation = location
        updateRouteProgress(location)
        maybeRecalculateRoute(location)
        val map = dashboardMap ?: return
        val position = LatLng(location.latitude, location.longitude)
        vehicleMarker?.position = position
        if (vehicleMarker == null) {
            vehicleMarker = map.addMarker(MarkerOptions().position(position).title("Posição atual"))
        }
        if (followVehicle) {
            val camera = CameraPosition.Builder()
                .target(position)
                .zoom(16.0)
                .apply { if (location.hasBearing()) bearing(location.bearing.toDouble()) }
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camera))
        } else if (map.cameraPosition.zoom < 8.0) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16.0))
        }
    }

    private fun recenterOnVehicle() {
        val location = lastLocation ?: return
        followVehicle = true
        dashboardMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 16.0)
        )
    }

    private fun searchAndDrawRoute(destination: String, onStatus: (String) -> Unit) {
        val origin = lastLocation
        if (origin == null) {
            onStatus("A aguardar a localização atual")
            return
        }
        if (destination.isBlank()) {
            onStatus("Escreva um destino")
            return
        }
        Thread {
            try {
                val address = Geocoder(this).getFromLocationName(destination, 1)?.firstOrNull()
                if (address == null) {
                    runOnUiThread { onStatus("Destino não encontrado") }
                    return@Thread
                }
                val destinationPoint = LatLng(address.latitude, address.longitude)
                val route = requestRoute(
                    LatLng(origin.latitude, origin.longitude),
                    destinationPoint
                )
                runOnUiThread {
                    if (route.points.isEmpty()) onStatus("Não foi possível calcular a rota")
                    else {
                        followVehicle = true
                        activeDestination = destinationPoint
                        activeRoutePoints = route.points
                        activeSteps = route.steps
                        activeStepIndex = 0
                        drawRoute(route.points)
                        onStatus(
                            "${formatDistance(route.distanceMeters)} · ${formatDuration(route.durationSeconds)}\n" +
                                "${route.steps.firstOrNull()?.instruction ?: "Siga pela rota"}\n" +
                                "Destino: ${address.getAddressLine(0) ?: destination}"
                        )
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { onStatus("Pesquisa indisponível. Verifique a ligação à internet") }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun requestRoute(origin: LatLng, destination: LatLng): RouteResult {
        val url = URL(
            "https://router.project-osrm.org/route/v1/driving/" +
                "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&steps=true"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "DriveDeck/0.9 Android navigation")
        }
        return try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val coordinates = route
                .getJSONObject("geometry")
                .getJSONArray("coordinates")
            val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            RouteResult(
                points = buildList(coordinates.length()) {
                for (index in 0 until coordinates.length()) {
                    val point = coordinates.getJSONArray(index)
                    add(LatLng(point.getDouble(1), point.getDouble(0)))
                }
                },
                distanceMeters = route.optDouble("distance", 0.0),
                durationSeconds = route.optDouble("duration", 0.0),
                steps = buildList(steps.length()) {
                    for (index in 0 until steps.length()) {
                        val step = steps.getJSONObject(index)
                        val maneuver = step.getJSONObject("maneuver")
                        val type = maneuver.optString("type")
                        val modifier = maneuver.optString("modifier")
                        val name = step.optString("name")
                        val location = maneuver.getJSONArray("location")
                        add(RouteStep(
                            formatInstruction(type, modifier, name),
                            LatLng(location.getDouble(1), location.getDouble(0))
                        ))
                    }
                }
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "%.0f m".format(meters)

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    private fun updateRouteProgress(location: Location) {
        if (activeSteps.isEmpty() || activeStepIndex >= activeSteps.size) return
        val step = activeSteps[activeStepIndex]
        val distance = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            step.position.latitude, step.position.longitude,
            distance
        )
        if (distance[0] <= 45f) {
            activeStepIndex++
            if (activeStepIndex >= activeSteps.size) {
                activeDestination = null
                activeRoutePoints = emptyList()
                routeStatusListener?.invoke("Chegou ao destino")
            } else {
                routeStatusListener?.invoke("Próxima: ${activeSteps[activeStepIndex].instruction}")
            }
        }
    }

    private fun maybeRecalculateRoute(location: Location) {
        val destination = activeDestination ?: return
        if (routeRequestInProgress || activeRoutePoints.isEmpty()) return
        val nearestDistance = activeRoutePoints.minOf { point ->
            val distance = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, point.latitude, point.longitude, distance)
            distance[0]
        }
        if (nearestDistance <= 120f) return

        routeRequestInProgress = true
        routeStatusListener?.invoke("Saiu da rota · a recalcular…")
        val origin = LatLng(location.latitude, location.longitude)
        Thread {
            try {
                val route = requestRoute(origin, destination)
                runOnUiThread {
                    if (route.points.isNotEmpty()) {
                        activeRoutePoints = route.points
                        activeSteps = route.steps
                        activeStepIndex = 0
                        drawRoute(route.points, focus = false)
                        routeStatusListener?.invoke(
                            "Rota recalculada\n${route.steps.firstOrNull()?.instruction ?: "Siga pela rota"}"
                        )
                    } else {
                        routeStatusListener?.invoke("Não foi possível recalcular a rota")
                    }
                    routeRequestInProgress = false
                }
            } catch (_: Exception) {
                runOnUiThread {
                    routeRequestInProgress = false
                    routeStatusListener?.invoke("Sem ligação para recalcular a rota")
                }
            }
        }.start()
    }

    private fun formatInstruction(type: String, modifier: String, street: String): String {
        val direction = when (modifier) {
            "left" -> "à esquerda"
            "right" -> "à direita"
            "slight left" -> "ligeiramente à esquerda"
            "slight right" -> "ligeiramente à direita"
            "sharp left" -> "acentuadamente à esquerda"
            "sharp right" -> "acentuadamente à direita"
            "uturn" -> "faça inversão de marcha"
            else -> "siga em frente"
        }
        val action = when (type) {
            "depart" -> "Comece"
            "arrive" -> "Chegou ao destino"
            "roundabout", "rotary" -> "Entre na rotunda"
            "merge" -> "Entre"
            else -> "Vire"
        }
        return if (type == "arrive") action else "$action $direction${street.takeIf { it.isNotBlank() }?.let { " para $it" } ?: ""}"
    }

    private data class RouteResult(
        val points: List<LatLng>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val steps: List<RouteStep>
    )

    private data class RouteStep(
        val instruction: String,
        val position: LatLng
    )

    private fun drawRoute(points: List<LatLng>, focus: Boolean = true) {
        val map = dashboardMap ?: return
        routePolyline?.let(map::removePolyline)
        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color(AndroidColor.rgb(70, 150, 255))
                .width(7f)
        )
        if (focus) points.firstOrNull()?.let { map.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 13.0)) }
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
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(Color(0xFF0B0D12))
                    .padding(10.dp)
            ) {
                val density = LocalDensity.current
                val availableHeightPx = with(density) { maxHeight.toPx() }
                val availableWidthPx = with(density) { maxWidth.toPx() }
                if (portrait) {
                    val dividerPx = with(density) { 12.dp.toPx() }
                    val usableHeight = (availableHeightPx - dividerPx).coerceAtLeast(1f)
                    val minMapFraction = (with(density) { 220.dp.toPx() } / usableHeight).coerceIn(0f, 1f)
                    val maxMapFraction = (1f - with(density) { 190.dp.toPx() } / usableHeight).coerceIn(0f, 1f)
                    val safeFraction = if (minMapFraction <= maxMapFraction) {
                        navigationFraction.coerceIn(minMapFraction, maxMapFraction)
                    } else {
                        0.5f
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapPane(
                            modifier = Modifier.weight(safeFraction).fillMaxWidth(),
                            navigationPackage = navigationPackage,
                            onRecenter = { recenterOnVehicle() },
                            onClose = { finish() }
                        )
                        SplitDivider(vertical = false) { delta ->
                            navigationFraction = (safeFraction + delta / usableHeight).coerceIn(0.20f, 0.88f)
                        }
                        PlayerPane(
                            modifier = Modifier.weight(1f - safeFraction).fillMaxWidth(),
                            track = track,
                            artist = artist,
                            playing = playing,
                            onPlayPause = { MusicController.playPause(context) },
                            onPrevious = { MusicController.previous(context) },
                            onNext = { MusicController.next(context) }
                        )
                    }
                } else {
                    val dividerPx = with(density) { 12.dp.toPx() }
                    val usableWidth = (availableWidthPx - dividerPx).coerceAtLeast(1f)
                    val minMapFraction = (with(density) { 320.dp.toPx() } / usableWidth).coerceIn(0f, 1f)
                    val maxMapFraction = (1f - with(density) { 260.dp.toPx() } / usableWidth).coerceIn(0f, 1f)
                    val safeFraction = if (minMapFraction <= maxMapFraction) {
                        navigationFraction.coerceIn(minMapFraction, maxMapFraction)
                    } else {
                        0.5f
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapPane(
                            modifier = Modifier.weight(safeFraction).fillMaxHeight(),
                            navigationPackage = navigationPackage,
                            onRecenter = { recenterOnVehicle() },
                            onClose = { finish() }
                        )
                        SplitDivider(vertical = true) { delta ->
                            navigationFraction = (safeFraction + delta / usableWidth).coerceIn(0.35f, 0.90f)
                        }
                        PlayerPane(
                            modifier = Modifier.weight(1f - safeFraction).fillMaxHeight(),
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
    private fun MapPane(
        modifier: Modifier,
        navigationPackage: String,
        onRecenter: () -> Unit,
        onClose: () -> Unit
    ) {
        val context = LocalContext.current
        val mapView = remember { MapView(context) }
        var destination by remember { mutableStateOf("") }
        var routeStatus by remember { mutableStateOf("") }
        DisposableEffect(Unit) {
            routeStatusListener = { status -> routeStatus = status }
            onDispose { routeStatusListener = null }
        }
        Card(modifier = modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121722))) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = {
                        dashboardMapView = mapView
                        mapView.onCreate(null)
                        mapView.getMapAsync { map ->
                            dashboardMap = map
                            configureMap(map)
                        }
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
                    Row {
                        IconButton(onClick = onRecenter) {
                            Icon(Icons.Rounded.MyLocation, "Centrar na posição atual", tint = Color.White)
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, "Fechar dashboard", tint = Color.White)
                        }
                    }
                }
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 68.dp, start = 12.dp, end = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE610151F))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Destino opcional") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        TextButton(
                            onClick = {
                                routeStatus = "A pesquisar…"
                                searchAndDrawRoute(destination) { status -> routeStatus = status }
                            }
                        ) { Text("Ir") }
                    }
                }
                if (routeStatus.isNotBlank()) {
                    Card(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp, start = 12.dp, end = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xCC10151F))
                    ) { Text(routeStatus, color = Color.White, modifier = Modifier.padding(10.dp), maxLines = 2) }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Modo condução ativo · destino opcional", color = Color.White, style = MaterialTheme.typography.labelSmall)
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
        lastLocation?.let { location ->
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(16.0)
                .build()
        }
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
