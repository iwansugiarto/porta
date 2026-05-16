package id.infinia.porta.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.infinia.porta.shared.protocol.ConnectionState
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: BridgeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) { null }
    }
    val versionName = packageInfo?.versionName ?: "unknown"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo?.longVersionCode?.toString() ?: "?"
    } else {
        @Suppress("DEPRECATION")
        packageInfo?.versionCode?.toString() ?: "?"
    }

    val connectionState by viewModel.connectionState.collectAsState()
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val useTls by viewModel.useTls.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PortaSurface)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(PortaSurface)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── App Identity ──
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(PortaPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = PortaPrimary
                )
            }

            Text(
                "Porta",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = PortaOnSurface
            )
            Text(
                "Remote AI Coding Bridge",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(4.dp))

            // ── App Info Card ──
            InfoCard(title = "Application") {
                InfoRow("Version", "$versionName ($versionCode)")
                InfoRow("Package", context.packageName)
                InfoRow("Build Type", if (context.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "Debug" else "Release")
                InfoRow("Min SDK", "28 (Android 9)")
                InfoRow("Target SDK", "35 (Android 15)")
            }

            // ── Device Info Card ──
            InfoCard(title = "Device") {
                InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow("Brand", Build.BRAND.replaceFirstChar { it.uppercase() })
                InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("Architecture", Build.SUPPORTED_ABIS.joinToString(", "))
                InfoRow("Board", Build.BOARD)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    InfoRow("SOC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
                }
                InfoRow("Display",
                    "${context.resources.displayMetrics.widthPixels}×${context.resources.displayMetrics.heightPixels}" +
                            " @ ${context.resources.displayMetrics.densityDpi}dpi")
            }

            // ── Connection Card ──
            InfoCard(title = "Connection") {
                InfoRow("Status", when (connectionState) {
                    ConnectionState.CONNECTED -> "✅ Connected"
                    ConnectionState.CONNECTING -> "⏳ Connecting"
                    ConnectionState.RECONNECTING -> "🔄 Reconnecting"
                    ConnectionState.ERROR -> "❌ Error"
                    ConnectionState.DISCONNECTED -> "⚪ Disconnected"
                })
                InfoRow("Proxy", "${if (useTls) "https" else "http"}://$host:$port")
                InfoRow("Protocol", "WebSocket ${if (useTls) "(WSS)" else "(WS)"}")
            }

            // ── Developer Card ──
            InfoCard(title = "Developer") {
                InfoRow("Company", "PT. Infinia Solusi Sistem")
                InfoRow("Domain", "infinia.id")
                InfoRow("Contact", "dev@infinia.id")
                InfoRow("License", "Proprietary")
            }

            // ── Tech Stack Card ──
            InfoCard(title = "Tech Stack") {
                InfoRow("UI", "Jetpack Compose + Material 3")
                InfoRow("Language", "Kotlin")
                InfoRow("Network", "OkHttp + Gson")
                InfoRow("Storage", "DataStore Preferences")
                InfoRow("Voice", "Android SpeechRecognizer + TTS")
                InfoRow("Bridge", "Antigravity LSP → Porta Proxy")
            }

            // Footer
            Spacer(Modifier.height(8.dp))
            Text(
                "© 2026 PT. Infinia Solusi Sistem\nAll rights reserved.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PortaSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PortaPrimary,
                letterSpacing = 0.5.sp
            )
            HorizontalDivider(color = PortaSurface, thickness = 1.dp)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.weight(0.35f)
        )
        Text(
            value,
            fontSize = 13.sp,
            color = PortaOnSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.65f)
        )
    }
}
