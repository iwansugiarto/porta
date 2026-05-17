package id.infinia.porta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: BridgeViewModel, onBack: () -> Unit, onNavigateToAbout: () -> Unit = {}, onNavigateToGlasses: () -> Unit = {}) {
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val authToken by viewModel.authToken.collectAsState()
    val useTls by viewModel.useTls.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val notifyEnabled by viewModel.notifyEnabled.collectAsState()
    val notifySound by viewModel.notifySound.collectAsState()
    val notifyVibrate by viewModel.notifyVibrate.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()

    var editHost by remember(host) { mutableStateOf(host) }
    var editPort by remember(port) { mutableStateOf(port.toString()) }
    var editToken by remember(authToken) { mutableStateOf(authToken ?: "") }
    var editUseTls by remember(useTls) { mutableStateOf(useTls) }
    var editAutoConnect by remember(autoConnect) { mutableStateOf(autoConnect) }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().background(MaterialTheme.colorScheme.surface)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Connect", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Connect automatically on app launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Switch(
                    checked = editAutoConnect,
                    onCheckedChange = {
                        editAutoConnect = it
                        viewModel.setAutoConnect(it)
                    },
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

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Server Profiles ──
            Text("Server Profiles", style = MaterialTheme.typography.titleMedium, color = PortaPrimary)

            val profiles by viewModel.serverProfiles.profiles.collectAsState()
            var profileName by remember { mutableStateOf("") }

            // Save current as profile
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    placeholder = { Text("Profile name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                FilledTonalButton(
                    onClick = {
                        if (profileName.isNotBlank()) {
                            viewModel.serverProfiles.createFromCurrentSettings(
                                name = profileName,
                                host = editHost,
                                port = editPort.toIntOrNull() ?: 443,
                                authToken = editToken.ifBlank { null },
                                useTls = editUseTls
                            )
                            profileName = ""
                            scope.launch {
                                snackbarHostState.showSnackbar("✅ Profile saved")
                            }
                        }
                    },
                    enabled = profileName.isNotBlank()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", fontSize = 13.sp)
                }
            }

            // Saved profiles list
            if (profiles.isNotEmpty()) {
                profiles.forEach { profile ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.switchToProfile(profile.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.isActive)
                                PortaPrimary.copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (profile.isActive) {
                                Icon(
                                    Icons.Default.CheckCircle, null,
                                    tint = PortaSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.Cloud, null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    profile.name,
                                    fontWeight = if (profile.isActive) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "${profile.host}:${profile.port}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            if (!profile.isActive) {
                                IconButton(
                                    onClick = { viewModel.serverProfiles.deleteProfile(profile.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete, "Delete",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Notification Settings ──
            Text("Notifications", style = MaterialTheme.typography.titleMedium, color = PortaPrimary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Task Completion Alert", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "System notification when agent finishes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Switch(
                    checked = notifyEnabled,
                    onCheckedChange = { viewModel.setNotifyEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = PortaPrimary)
                )
            }

            // Sub-options (only visible when notifications are enabled)
            if (notifyEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Sound", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = notifySound,
                        onCheckedChange = { viewModel.setNotifySound(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = PortaPrimary)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Vibration, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Vibrate", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = notifyVibrate,
                        onCheckedChange = { viewModel.setNotifyVibrate(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = PortaPrimary)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Theme Settings ──
            Text("Appearance", style = MaterialTheme.typography.titleMedium, color = PortaPrimary)

            val themeMode by viewModel.themeMode.collectAsState()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                data class ThemeOption(
                    val mode: id.infinia.porta.ui.theme.ThemeMode,
                    val label: String,
                    val icon: androidx.compose.ui.graphics.vector.ImageVector
                )
                val options = listOf(
                    ThemeOption(id.infinia.porta.ui.theme.ThemeMode.SYSTEM, "System", Icons.Default.BrightnessAuto),
                    ThemeOption(id.infinia.porta.ui.theme.ThemeMode.LIGHT, "Light", Icons.Default.LightMode),
                    ThemeOption(id.infinia.porta.ui.theme.ThemeMode.DARK, "Dark", Icons.Default.DarkMode),
                )
                options.forEach { option ->
                    FilterChip(
                        selected = themeMode == option.mode,
                        onClick = { viewModel.setThemeMode(option.mode) },
                        label = { Text(option.label) },
                        leadingIcon = {
                            Icon(option.icon, null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PortaPrimary.copy(alpha = 0.15f),
                            selectedLabelColor = PortaPrimary,
                            selectedLeadingIconColor = PortaPrimary
                        )
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Voice Settings ──
            Text("Voice Input", style = MaterialTheme.typography.titleMedium, color = PortaPrimary)

            val voiceLanguage by viewModel.voiceLanguage.collectAsState()
            var languageDropdownOpen by remember { mutableStateOf(false) }

            val currentLabel = viewModel.voiceLanguageOptions
                .firstOrNull { it.first == voiceLanguage }?.second ?: voiceLanguage

            Box {
                OutlinedButton(
                    onClick = { languageDropdownOpen = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Language: $currentLabel")
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                }

                DropdownMenu(
                    expanded = languageDropdownOpen,
                    onDismissRequest = { languageDropdownOpen = false }
                ) {
                    viewModel.voiceLanguageOptions.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(label)
                                    Text(
                                        code,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            },
                            onClick = {
                                viewModel.setVoiceLanguage(code)
                                languageDropdownOpen = false
                            },
                            leadingIcon = {
                                if (code == voiceLanguage) {
                                    Icon(
                                        Icons.Default.Check, null,
                                        modifier = Modifier.size(16.dp),
                                        tint = PortaSuccess
                                    )
                                }
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Text("AR Glasses", style = MaterialTheme.typography.titleMedium, color = PortaSecondary)

            val glassesState by viewModel.glassesState.collectAsState()
            val glassesProviderName by viewModel.glassesProviderName.collectAsState()

            OutlinedButton(
                onClick = onNavigateToGlasses,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Visibility, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (glassesState) {
                        id.infinia.porta.service.glasses.GlassesState.SCENE_ACTIVE -> "Connected — $glassesProviderName"
                        id.infinia.porta.service.glasses.GlassesState.CONNECTED -> "Connected"
                        id.infinia.porta.service.glasses.GlassesState.CONNECTING -> "Connecting..."
                        else -> "Manage Glasses"
                    }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── About ──
            OutlinedButton(
                onClick = onNavigateToAbout,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("About Porta")
            }
        }
    }
}
