package com.porta.rokid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.porta.rokid.ui.theme.*
import com.porta.rokid.viewmodel.BridgeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: BridgeViewModel, onBack: () -> Unit) {
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val authToken by viewModel.authToken.collectAsState()
    val useTls by viewModel.useTls.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var editHost by remember(host) { mutableStateOf(host) }
    var editPort by remember(port) { mutableStateOf(port.toString()) }
    var editToken by remember(authToken) { mutableStateOf(authToken ?: "") }
    var editUseTls by remember(useTls) { mutableStateOf(useTls) }
    var connectAttempted by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Watch for load completion after connect attempt
    LaunchedEffect(isLoading, connectAttempted) {
        if (connectAttempted && !isLoading) {
            connectAttempted = false
            if (statusMessage.startsWith("Loaded")) {
                snackbarHostState.showSnackbar("✅ $statusMessage")
                delay(600)
                onBack()
            } else if (statusMessage.startsWith("Failed")) {
                snackbarHostState.showSnackbar("❌ $statusMessage")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PortaSurface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().background(PortaSurface)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Porta Proxy Connection", style = MaterialTheme.typography.titleMedium, color = PortaPrimary)

            OutlinedTextField(
                value = editHost, onValueChange = { editHost = it },
                label = { Text("Host") }, placeholder = { Text("proxy.example.com") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = editPort, onValueChange = { editPort = it },
                label = { Text("Port") }, placeholder = { Text("443") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = editToken, onValueChange = { editToken = it },
                label = { Text("Auth Token (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("TLS/SSL", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (editUseTls) "https:// / wss://" else "http:// / ws://",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Switch(
                    checked = editUseTls,
                    onCheckedChange = { editUseTls = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = PortaPrimary)
                )
            }

            Button(
                onClick = {
                    connectAttempted = true
                    viewModel.updateConfig(
                        editHost,
                        editPort.toIntOrNull() ?: if (editUseTls) 443 else 3170,
                        editToken.ifBlank { null },
                        editUseTls
                    )
                    viewModel.connect()
                },
                enabled = !isLoading && editHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PortaPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading && connectAttempted) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting...")
                } else {
                    Icon(Icons.Default.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect")
                }
            }

            // Status feedback
            if (isLoading && connectAttempted) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = PortaPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(color = PortaSurfaceVariant)

            Text("Rokid Glasses", style = MaterialTheme.typography.titleMedium, color = PortaSecondary)
            Text("CXR SDK integration coming soon.\nDevelop and test with the phone app first.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
