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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.glassesrelay.ui.theme.CyanAccent
import com.example.glassesrelay.ui.theme.GlassesRelayTheme
import com.example.glassesrelay.ui.theme.StatusAmber
import com.example.glassesrelay.ui.theme.StatusGreen
import com.example.glassesrelay.ui.theme.StatusRed
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

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
        super.onCreate(savedInstanceState)
        Wearables.initialize(this)
        requestBluetoothPermissionIfNeeded()

        setContent {
            GlassesRelayTheme {
                GlassesRelayApp(
                    serviceStreamState = serviceStreamState,
                    onStartStream = { rtmpUrl -> startStreamingService(rtmpUrl) },
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

    private fun startStreamingService(rtmpUrl: String) {
        val serviceIntent = Intent(this, StreamingService::class.java).apply {
            putExtra(StreamingService.EXTRA_RTMP_URL, rtmpUrl)
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

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassesRelayApp(
    serviceStreamState: StateFlow<StreamingService.StreamState>,
    onStartStream: (String) -> Unit,
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(300))
            ) {
                StreamStatusCard(
                    isStreaming = isStreaming,
                    isConnecting = isConnecting,
                    fps = streamState.fps,
                    statusMessage = streamState.statusMessage,
                    errorMessage = streamState.errorMessage
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            StreamControlButton(
                isStreaming = isStreaming,
                isConnecting = isConnecting,
                enabled = isConnected && rtmpUrl.length > 7,
                onClick = {
                    if (isStreaming || isConnecting) {
                        onStopStream()
                    } else {
                        onStartStream(rtmpUrl)
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
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
                else -> "Start Streaming"
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}