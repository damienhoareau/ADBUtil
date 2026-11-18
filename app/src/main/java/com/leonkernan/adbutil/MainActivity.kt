package com.leonkernan.adbutil

import android.content.ContentResolver
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonkernan.adbutil.ui.theme.ADBUtilTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val sysPropCls by lazy { Class.forName("android.os.SystemProperties") }
private val sysPropGetMethod by lazy { sysPropCls.getMethod("get", String::class.java, String::class.java) }
private val sysPropSetMethod by lazy { sysPropCls.getMethod("set", String::class.java, String::class.java) }

private fun getSystemProperty(key: String, default: String = ""): String = try {
    sysPropGetMethod.invoke(null, key, default) as String
} catch (e: java.lang.reflect.InvocationTargetException) {
    throw e.cause ?: e
}

private fun setSystemProperty(key: String, value: String) = try {
    sysPropSetMethod.invoke(null, key, value)
} catch (e: java.lang.reflect.InvocationTargetException) {
    throw e.cause ?: e
}

private fun execSetprop(key: String, value: String) {
    val proc = Runtime.getRuntime().exec(arrayOf("setprop", key, value))
    val exit = proc.waitFor()
    if (exit != 0) {
        val err = proc.errorStream.bufferedReader().readText().trim()
        throw RuntimeException("setprop $key failed (exit $exit)${if (err.isNotEmpty()) ": $err" else ""}")
    }
}

data class PropState(
    val adbEnabled: String,
    val otgGadget: String,
    val usbConfig: String,
    val adbdRunning: Boolean,
)

private fun readState(resolver: ContentResolver): PropState {
    val adbEnabled = try {
        Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0).toString()
    } catch (e: Exception) {
        "error: ${e.message}"
    }
    val otgGadget = getSystemProperty("persist.sys.service.otg_gadget", "<not set>")
    val usbConfig = getSystemProperty("sys.usb.config", "<not set>")
    val usbState = getSystemProperty("sys.usb.state", "")
    val adbdRunning = usbState.contains("adb")
    return PropState(adbEnabled, otgGadget, usbConfig, adbdRunning)
}

internal fun applyAdb(resolver: ContentResolver): Result<Unit> = runCatching {
    val delay = 500L
    Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
    Thread.sleep(delay)
    execSetprop("persist.sys.service.otg_gadget", "1")
    Thread.sleep(delay)
    execSetprop("allgo.usb.interface", "up")
    Thread.sleep(delay)
    execSetprop("sys.usb.config", "none")
    Thread.sleep(delay)
    execSetprop("sys.usb.config", "mass_storage,adb")
    Thread.sleep(delay)
    execSetprop("ctl.restart", "adbd")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val resolver = contentResolver
        setContent {
            ADBUtilTheme {
                AdbScreen(resolver)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbScreen(resolver: ContentResolver) {
    var state by remember { mutableStateOf<PropState?>(null) }
    var applying by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            state = withContext(Dispatchers.IO) { readState(resolver) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ADB Enabler") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Property State", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            val s = state
            if (s == null) {
                CircularProgressIndicator()
            } else {
                PropRow("Settings.Global.adb_enabled", s.adbEnabled)
                PropRow("persist.sys.service.otg_gadget", s.otgGadget)
                PropRow("sys.usb.config", s.usbConfig)
                PropRow("adbd running", if (s.adbdRunning) "yes" else "no",
                    highlight = s.adbdRunning)
            }

            Spacer(Modifier.height(8.dp))

            if (statusMsg.isNotEmpty()) {
                Text(
                    statusMsg,
                    color = if (statusMsg.startsWith("Error")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        applying = true
                        statusMsg = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { applyAdb(resolver) }
                            delay(600)
                            state = withContext(Dispatchers.IO) { readState(resolver) }
                            statusMsg = result.fold(
                                onSuccess = { "Done — properties applied." },
                                onFailure = { "Error: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}" }
                            )
                            applying = false
                        }
                    },
                    enabled = !applying
                ) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Enable ADB")
                }

                OutlinedButton(
                    onClick = {
                        applying = true
                        statusMsg = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { execSetprop("ctl.restart", "adbd") }
                            }
                            delay(600)
                            state = withContext(Dispatchers.IO) { readState(resolver) }
                            statusMsg = result.fold(
                                onSuccess = { "adbd restarted." },
                                onFailure = { "Error: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}" }
                            )
                            applying = false
                        }
                    },
                    enabled = !applying
                ) {
                    Text("Restart ADB")
                }

                OutlinedButton(onClick = { refresh() }, enabled = !applying) {
                    Text("Refresh")
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text(
                "Applies in order:\n" +
                        "1. adb_enabled = 1\n" +
                        "2. persist.sys.service.otg_gadget = 1\n" +
                        "3. allgo.usb.interface = up\n" +
                        "4. sys.usb.config = none  →  mass_storage,adb\n" +
                        "5. ctl.restart = adbd",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            NetworkAdbSection()

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            FreePortsSection()
        }
    }
}

@Composable
private fun NetworkAdbSection() {
    val context = LocalContext.current.applicationContext
    var working by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val port = NetworkAdb.DEFAULT_PORT

    Text("Network ADB", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(
        "Restarts adbd on TCP port $port, the only port the car firewall lets through that " +
                "is free. Run \"Enable ADB\" first, then approve the RSA prompt on the head unit. " +
                "The port reverts to default when adbd restarts or the car reboots.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(
        onClick = {
            working = true
            msg = ""
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { NetworkAdb.enable(context, port) } }
                msg = r.fold(
                    onSuccess = { "adbd listening on ${it.port}. On your computer: adb connect ${it.address ?: "<wlan ip>"}:${it.port}" },
                    onFailure = { "Error: ${it.message ?: it.javaClass.simpleName}" }
                )
                working = false
            }
        },
        enabled = !working
    ) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
        }
        Text("Enable Network ADB ($port)")
    }
    if (msg.isNotEmpty()) {
        Text(
            msg,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = if (msg.startsWith("Error")) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun FreePortsSection() {
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PortScanResult?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Text("Free WiFi Ports", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(
        "Tests, on the wlan interface's address, only the TCP ports the car's own " +
                "firewall (arp_update.sh) lets through — anything else is rejected before " +
                "it reaches any service, so it is not worth probing.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
        onClick = {
            errorMsg = ""
            scanning = true
            result = null
            scope.launch {
                val scanResult = withContext(Dispatchers.IO) {
                    val address = getWifiIPv4Address()
                    if (address == null) {
                        null
                    } else {
                        val (ports, source) = CarFirewall.allowedPorts()
                        scanPorts(address, ports, source)
                    }
                }
                if (scanResult == null) {
                    errorMsg = "No wlan interface with an IPv4 address found."
                } else {
                    result = scanResult
                }
                scanning = false
            }
        },
        enabled = !scanning
    ) {
        if (scanning) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
        }
        Text("Scan Allowed Ports")
    }

    if (errorMsg.isNotEmpty()) {
        Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
    }

    result?.let { r ->
        Text(
            "Interface address: ${r.address.hostAddress}",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "Candidate ports: ${r.source}.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Free: " + if (r.freePorts.isEmpty()) "none" else r.freePorts.joinToString(", "),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Busy: " + if (r.busyPorts.isEmpty()) "none" else r.busyPorts.joinToString(", "),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PropRow(key: String, value: String, highlight: Boolean = false) {
    val isOk = value == "1" || value == "mass_storage,adb" || highlight
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(key, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .padding(0.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (isOk) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.fillMaxSize()
                ) {}
            }
        }
    }
}
