package com.dihax.androidhttpserver

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.dihax.androidhttpserver.server.RequestLog
import com.dihax.androidhttpserver.server.getLocalIpAddress
import com.dihax.androidhttpserver.ui.theme.HTTPSERVERTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    var serverService: HttpServerService? = null

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning = _serverRunning.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HttpServerService.LocalBinder
            serverService = binder.getService()

            lifecycleScope.launch {
                serverService?.isRunning?.collect { running ->
                    _serverRunning.value = running
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serverService = null
            _serverRunning.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HTTPSERVERTheme {
                ServerScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, HttpServerService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
    }

    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return permissions.toTypedArray()
    }

    fun checkPermissions(): Boolean {
        val requiredPermissions = getRequiredPermissions()
        val runtimeGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        return runtimeGranted && storageGranted
    }

    fun needsManageStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
    }

    fun requestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

@Composable
fun ServerScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboard.current

    var portText by remember { mutableStateOf("8080") }
    var hasPermissions by remember { mutableStateOf(false) }
    var isServerRunning by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        hasPermissions = allGranted
        if (allGranted) {
            Toast.makeText(context, "Permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissions required for server.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        hasPermissions = activity.checkPermissions()
    }

    val serverRunning by activity.serverRunning.collectAsState()

    LaunchedEffect(serverRunning) {
        isServerRunning = serverRunning
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Phylax",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        PermissionsCard(
            hasPermissions = hasPermissions,
            onRequestPermissions = {
                val requiredPermissions = activity.getRequiredPermissions()
                if (requiredPermissions.isNotEmpty()) {
                    permissionLauncher.launch(requiredPermissions)
                }
            }
        )

        if (activity.needsManageStoragePermission()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Storage Access", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "File server needs access to device storage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { activity.requestManageStorage() }) {
                        Text("Grant Storage Access")
                    }
                }
            }
        }

        ServerControlsCard(
            portText = portText,
            onPortTextChange = { portText = it },
            hasPermissions = hasPermissions,
            isServerRunning = isServerRunning,
            onStartServer = {
                if (activity.checkPermissions()) {
                    val port = portText.toIntOrNull() ?: 8080
                    val intent = Intent(context, HttpServerService::class.java).apply {
                        action = HttpServerService.ACTION_START_SERVER
                        putExtra(HttpServerService.EXTRA_PORT, port)
                    }
                    try {
                        context.startService(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "Grant permissions first", Toast.LENGTH_SHORT).show()
                }
            },
            onStopServer = {
                val intent = Intent(context, HttpServerService::class.java).apply {
                    action = HttpServerService.ACTION_STOP_SERVER
                }
                context.startService(intent)
            }
        )

        ServerAddressCard(
            portText = portText,
            isServerRunning = isServerRunning,
            onCopyAddress = { serverUrl ->
                coroutineScope.launch {
                    val clipEntry = ClipEntry(ClipData.newPlainText("serverUrl", serverUrl))
                    clipboardManager.setClipEntry(clipEntry)
                }
                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
            }
        )

        ActivityLogCard(isServerRunning = isServerRunning)

        DeveloperInfoCard()
    }
}

@Composable
fun PermissionsCard(hasPermissions: Boolean, onRequestPermissions: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hasPermissions) "All permissions granted" else "Permissions required",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasPermissions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            if (!hasPermissions) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRequestPermissions) { Text("Grant Permissions") }
            }
        }
    }
}

@Composable
fun ServerControlsCard(
    portText: String,
    onPortTextChange: (String) -> Unit,
    hasPermissions: Boolean,
    isServerRunning: Boolean,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Server Controls", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = onPortTextChange,
                label = { Text("Port Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartServer,
                    modifier = Modifier.weight(1f),
                    enabled = hasPermissions && !isServerRunning
                ) { Text("Start") }
                Button(
                    onClick = onStopServer,
                    modifier = Modifier.weight(1f),
                    enabled = isServerRunning
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
fun ServerAddressCard(
    portText: String,
    isServerRunning: Boolean,
    onCopyAddress: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Server Address", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val serverUrl = "http://${getLocalIpAddress()}:$portText"
            Text(
                text = serverUrl,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (isServerRunning) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isServerRunning) "Running" else "Stopped",
                style = MaterialTheme.typography.bodySmall,
                color = if (isServerRunning) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { onCopyAddress(serverUrl) }, enabled = isServerRunning) {
                Text("Copy Address")
            }
        }
    }
}

@Composable
fun ActivityLogCard(isServerRunning: Boolean) {
    val logEntries by RequestLog.entries.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Activity Log", style = MaterialTheme.typography.titleMedium)
                if (logEntries.isNotEmpty()) {
                    TextButton(onClick = { RequestLog.clear() }) {
                        Text("Clear", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!isServerRunning && logEntries.isEmpty()) {
                Text(
                    "Start the server to see activity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (logEntries.isEmpty()) {
                Text(
                    "No requests yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logEntries.take(20).forEach { entry ->
                        val statusColor = when {
                            entry.status in 200..299 -> MaterialTheme.colorScheme.primary
                            entry.status in 400..499 -> MaterialTheme.colorScheme.error
                            entry.status >= 500 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = timeFormat.format(Date(entry.timestamp)),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = entry.status.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = statusColor
                            )
                            Text(
                                text = "${entry.method} ${entry.path}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeveloperInfoCard() {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Developer", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Anubhav Gain", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Security Software Engineer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/mranv".toUri()))
            }) { Text("GitHub: mranv") }
        }
    }
}
