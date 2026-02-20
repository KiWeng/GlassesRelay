package com.example.glassesrelay

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.glassesrelay.ui.theme.*
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    // ── Service binding ─────────────────────────────────────────────
    private var streamingService: StreamingService? = null
    private var serviceBound = false

    private val _serviceStreamState = MutableStateFlow(StreamingService.StreamState())
    val serviceStreamState: StateFlow<StreamingService.StreamState> = _serviceStreamState

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as StreamingService.LocalBinder
            streamingService = localBinder.service
            serviceBound = true
            // Forward service state to our local flow for Compose observation
            val service = localBinder.service
            lifecycleScope.launch {
                service.streamState.collect { state ->
                    _serviceStreamState.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            serviceBound = false
            _serviceStreamState.value = StreamingService.StreamState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // wearables initialization
        Wearables.initialize(this)
        requestPermissionsIfNeeded()

        setContent {
            GlassesRelayTheme {
                GlassesRelayApp(
                    serviceStreamState = serviceStreamState,
                    onStartStream = { rtmpUrl, quality, fps -> startStreamingService(rtmpUrl, quality, fps) },
                    onStopStream = { stopStreamingService() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service if it's already running
        Intent(this, StreamingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun startStreamingService(rtmpUrl: String, quality: String, fps: Int) {
        val serviceIntent = Intent(this, StreamingService::class.java).apply {
            putExtra(StreamingService.EXTRA_RTMP_URL, rtmpUrl)
            putExtra(StreamingService.EXTRA_VIDEO_QUALITY, quality)
            putExtra(StreamingService.EXTRA_VIDEO_FPS, fps)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // Bind so we can observe state
        if (!serviceBound) {
            bindService(
                Intent(this, StreamingService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    private fun stopStreamingService() {
        streamingService?.stopStreaming()
        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassesRelayApp(
    serviceStreamState: StateFlow<StreamingService.StreamState>,
    onStartStream: (String, String, Int) -> Unit,
    onStopStream: () -> Unit
) {
    val activity = LocalContext.current as Activity

    // Observe available devices from the SDK
    val devices by Wearables.devices.collectAsState()
    val isConnected = devices.isNotEmpty()
    val deviceName = devices.firstOrNull()?.toString()

    // Observe registration state
    val registrationState by Wearables.registrationState.collectAsState()

    // Observe streaming service state
    val streamState by serviceStreamState.collectAsState()
    val isStreaming = streamState.isStreaming
    val isConnecting = streamState.isConnecting

    var rtmpUrl by rememberSaveable { mutableStateOf("rtmp://") }
    var selectedQuality by rememberSaveable { mutableStateOf("HIGH") }
    var selectedFps by rememberSaveable { mutableStateOf(30) }

    var isFullScreen by remember { mutableStateOf(false) }
    var showFullScreenControls by remember { mutableStateOf(true) }

    // Auto-hide full-screen controls
    LaunchedEffect(showFullScreenControls, isFullScreen) {
        if (isFullScreen && showFullScreenControls) {
            delay(3000L)
            showFullScreenControls = false
        }
    }

    // Set system bars to transparent and handle edge-to-edge
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // On OnePlus/ColorOS, we want to ensure icons are readable.
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // Launcher for Meta SDK Camera Permission
    val metaCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = Wearables.RequestPermissionContract()
    ) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        android.widget.Toast.makeText(activity, "Status: $permissionStatus", android.widget.Toast.LENGTH_LONG).show()
        if (permissionStatus == PermissionStatus.Granted) {
            onStartStream(rtmpUrl, selectedQuality, selectedFps)
        } else {
            Log.e("GlassesRelay", "Meta Camera Permission denied: $permissionStatus")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent, // Let the background show through
            contentWindowInsets = WindowInsets(0)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding() // Push content below status bar
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp)) // Small extra gap
                AppHeader(isConnected = isConnected)
                Spacer(modifier = Modifier.height(28.dp))

                ConnectionCard(
                    isConnected = isConnected,
                    deviceName = deviceName,
                    registrationStatus = registrationState.toString(),
                    onAuthorize = { Wearables.startRegistration(activity) }
                )
                Spacer(modifier = Modifier.height(16.dp))

                RtmpConfigCard(
                    rtmpUrl = rtmpUrl,
                    onUrlChange = { rtmpUrl = it },
                    enabled = !isStreaming && !isConnecting
                )
                Spacer(modifier = Modifier.height(16.dp))

                StreamSettingsCard(
                    selectedQuality = selectedQuality,
                    onQualityChange = { selectedQuality = it },
                    selectedFps = selectedFps,
                    onFpsChange = { selectedFps = it },
                    enabled = !isStreaming && !isConnecting
                )
                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = isConnected,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(300))
                ) {
                    Column {
                        StreamStatusCard(
                            isStreaming = isStreaming,
                            isConnecting = isConnecting,
                            fps = streamState.fps,
                            statusMessage = streamState.statusMessage,
                            errorMessage = streamState.errorMessage
                        )
                        
                        if (isStreaming && streamState.latestFrame != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(400.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = streamState.latestFrame!!.asImageBitmap(),
                                        contentDescription = "Live Video Stream",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    
                                    // Expand button
                                    IconButton(
                                        onClick = { 
                                            isFullScreen = true 
                                            showFullScreenControls = true
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(12.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Fullscreen,
                                            contentDescription = "Full Screen",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))

                StreamControlButton(
                    isStreaming = isStreaming,
                    isConnecting = isConnecting,
                    enabled = isConnected,
                    registrationState = registrationState,
                    onClick = {
                        if (isStreaming || isConnecting) {
                            onStopStream()
                        } else {
                            if (registrationState is RegistrationState.Registered) {
                                // Request Meta SDK camera permission before starting the stream
                                metaCameraPermissionLauncher.launch(Permission.CAMERA)
                            } else {
                                // Launch the Meta SDK Registration flow
                                Wearables.startRegistration(activity)
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(24.dp))
                // ... 
                Spacer(
                    modifier = Modifier
                        .height(32.dp)
                        .navigationBarsPadding() // Safe area for navigation bar
                )
            }
        }

        if (isFullScreen && streamState.latestFrame != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .zIndex(100f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showFullScreenControls = !showFullScreenControls
                    }
            ) {
                Image(
                    bitmap = streamState.latestFrame!!.asImageBitmap(),
                    contentDescription = "Full Screen Live Video",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit // Fit entire video in full screen
                )
                
                // Exit button
                AnimatedVisibility(
                    visible = showFullScreenControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomEnd) // Moved to bottom-right
                ) {
                    IconButton(
                        onClick = { isFullScreen = false },
                        modifier = Modifier
                            .padding(bottom = 48.dp, end = 24.dp) // Adjusted padding for bottom-right
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Exit Full Screen",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(isConnected: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GlassesRelay",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = CyanAccent
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Ray-Ban → RTMP Video Bridge",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        StatusPill(isConnected = isConnected)
    }
}

@Composable
private fun StatusPill(isConnected: Boolean) {
    val dotColor by animateColorAsState(
        targetValue = if (isConnected) StatusGreen else StatusRed,
        animationSpec = tween(500), label = "statusDot"
    )
    val pulseAlpha = if (!isConnected) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "pulseAlpha"
        ).value
    } else 1f

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(pulseAlpha)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isConnected) "Glasses Connected" else "No Device",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    isConnected: Boolean,
    deviceName: String?,
    registrationStatus: String,
    onAuthorize: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isConnected) Icons.Filled.BluetoothConnected
                    else Icons.Filled.Bluetooth,
                    contentDescription = "Bluetooth",
                    tint = if (isConnected) StatusGreen
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Device Connection",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (isConnected && deviceName != null) {
                Text(
                    text = "Connected to $deviceName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusGreen
                )
            } else {
                Text(
                    text = "Tap below to authorize your Ray-Ban Meta glasses through the Meta AI app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Registration: $registrationStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onAuthorize,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "Re-authorize Glasses"
                    else "Authorize Glasses via Meta AI",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun StreamSettingsCard(
    selectedQuality: String,
    onQualityChange: (String) -> Unit,
    selectedFps: Int,
    onFpsChange: (Int) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Stream Configuration",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Quality Selection
            Text("Video Quality", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LOW", "MEDIUM", "HIGH").forEach { quality ->
                    val isSelected = selectedQuality == quality
                    FilterChip(
                        selected = isSelected,
                        onClick = { if (enabled) onQualityChange(quality) },
                        label = { Text(quality) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanAccent,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // FPS Selection
            Text("Target FPS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { fps ->
                    val isSelected = selectedFps == fps
                    FilterChip(
                        selected = isSelected,
                        onClick = { if (enabled) onFpsChange(fps) },
                        label = { Text("$fps FPS") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanAccent,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RtmpConfigCard(
    rtmpUrl: String,
    onUrlChange: (String) -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CellTower,
                    contentDescription = "RTMP",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "RTMP Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = rtmpUrl,
                onValueChange = onUrlChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("rtmp://your-server/live/key") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    cursorColor = CyanAccent,
                    focusedLabelColor = CyanAccent,
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "720×1280 • 9:16 vertical • up to 30 FPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StreamStatusCard(
    isStreaming: Boolean,
    isConnecting: Boolean,
    fps: Int,
    statusMessage: String,
    errorMessage: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isStreaming -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                errorMessage != null -> StatusRed.copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = "Stream",
                    tint = when {
                        isStreaming -> StatusGreen
                        isConnecting -> StatusAmber
                        errorMessage != null -> StatusRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Stream Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusRed
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusMetric(
                    icon = Icons.Outlined.Speed,
                    label = "FPS",
                    value = if (isStreaming) "$fps" else "0"
                )
                StatusMetric(
                    icon = Icons.Filled.Videocam,
                    label = "Resolution",
                    value = "720×1280"
                )
                StatusMetric(
                    icon = Icons.Filled.CellTower,
                    label = "Status",
                    value = when {
                        isStreaming -> "LIVE"
                        isConnecting -> "CONNECTING"
                        errorMessage != null -> "ERROR"
                        else -> "IDLE"
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusMetric(
    icon: ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StreamControlButton(
    isStreaming: Boolean,
    isConnecting: Boolean,
    enabled: Boolean,
    registrationState: RegistrationState,
    onClick: () -> Unit
) {
    val isActive = isStreaming || isConnecting

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isStreaming -> StatusRed
                isConnecting -> StatusAmber
                else -> CyanAccent
            },
            contentColor = when {
                isStreaming -> MaterialTheme.colorScheme.onError
                else -> MaterialTheme.colorScheme.onSecondary
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        Icon(
            imageVector = if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                isStreaming -> "Stop Streaming"
                isConnecting -> "Cancel"
                registrationState !is RegistrationState.Registered -> "Register Device First"
                else -> "Start Streaming"
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}