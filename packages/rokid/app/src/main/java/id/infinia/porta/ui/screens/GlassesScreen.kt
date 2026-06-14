package id.infinia.porta.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.infinia.porta.service.glasses.GlassesCapabilities
import id.infinia.porta.service.glasses.GlassesState
import id.infinia.porta.ui.theme.*
import id.infinia.porta.viewmodel.BridgeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesScreen(viewModel: BridgeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val glassesState by viewModel.glassesState.collectAsState()
    val glassesError by viewModel.glassesError.collectAsState()
    val glassesCapabilities by viewModel.glassesCapabilities.collectAsState()
    val glassesProviderName by viewModel.glassesProviderName.collectAsState()
    val autoForwardToGlasses by viewModel.autoForwardToGlasses.collectAsState()
    val autoConnectGlasses by viewModel.autoConnectGlasses.collectAsState()
    val glassesAlignment by viewModel.glassesAlignment.collectAsState()
    val glassesFontSize by viewModel.glassesFontSize.collectAsState()
    val glassesPadding by viewModel.glassesPadding.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AR Glasses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Status Card ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                when (glassesState) {
                                    GlassesState.CONNECTED, GlassesState.SCENE_ACTIVE ->
                                        PortaSuccess.copy(alpha = 0.15f)
                                    GlassesState.CONNECTING ->
                                        PortaWarning.copy(alpha = 0.15f)
                                    GlassesState.ERROR ->
                                        PortaError.copy(alpha = 0.15f)
                                    GlassesState.DISCONNECTED ->
                                        PortaPrimary.copy(alpha = 0.1f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when (glassesState) {
                                GlassesState.CONNECTED, GlassesState.SCENE_ACTIVE ->
                                    Icons.Default.Visibility
                                GlassesState.CONNECTING ->
                                    if (glassesProviderName.contains("USB"))
                                        Icons.Default.Usb
                                    else
                                        Icons.AutoMirrored.Filled.BluetoothSearching
                                GlassesState.ERROR ->
                                    Icons.Default.ErrorOutline
                                GlassesState.DISCONNECTED ->
                                    Icons.Default.VisibilityOff
                            },
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = when (glassesState) {
                                GlassesState.CONNECTED, GlassesState.SCENE_ACTIVE -> PortaSuccess
                                GlassesState.CONNECTING -> PortaWarning
                                GlassesState.ERROR -> PortaError
                                GlassesState.DISCONNECTED -> PortaPrimary.copy(alpha = 0.5f)
                            }
                        )
                    }

                    Text(
                        when (glassesState) {
                            GlassesState.DISCONNECTED -> "Not Connected"
                            GlassesState.CONNECTING -> "Connecting..."
                            GlassesState.CONNECTED -> "Connected"
                            GlassesState.SCENE_ACTIVE -> "Active — Ready"
                            GlassesState.ERROR -> "Error"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        "Provider: $glassesProviderName",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    // Error message
                    if (glassesState == GlassesState.ERROR && glassesError != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = PortaError.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                glassesError!!,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 12.sp,
                                color = PortaError
                            )
                        }
                    }

                    // Connect / Disconnect button
                    Button(
                        onClick = {
                            when (glassesState) {
                                GlassesState.DISCONNECTED, GlassesState.ERROR ->
                                    viewModel.connectGlasses(context)
                                else -> viewModel.disconnectGlasses()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (glassesState == GlassesState.DISCONNECTED || glassesState == GlassesState.ERROR)
                                PortaPrimary else PortaError
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = glassesState != GlassesState.CONNECTING
                    ) {
                        if (glassesState == GlassesState.CONNECTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting...")
                        } else if (glassesState == GlassesState.DISCONNECTED || glassesState == GlassesState.ERROR) {
                            Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect")
                        } else {
                            Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Disconnect")
                        }
                    }
                }
            }

            // ── Capabilities ──
            AnimatedVisibility(
                visible = glassesState == GlassesState.SCENE_ACTIVE || glassesState == GlassesState.CONNECTED,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Capabilities", style = MaterialTheme.typography.titleSmall, color = PortaPrimary)

                    CapabilityRow("Display", glassesCapabilities.canDisplay, Icons.Default.Tv)
                    CapabilityRow("Audio Capture", glassesCapabilities.canStreamAudio, Icons.Default.Mic)
                    CapabilityRow("Photo Capture", glassesCapabilities.canCapturePhoto, Icons.Default.CameraAlt)
                    CapabilityRow("Custom Commands", glassesCapabilities.canSendCommands, Icons.Default.Terminal)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Settings ──
            Text("Settings", style = MaterialTheme.typography.titleSmall, color = PortaPrimary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Forward Responses", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Display AI responses on glasses automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Switch(
                    checked = autoForwardToGlasses,
                    onCheckedChange = { viewModel.setAutoForwardToGlasses(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = PortaPrimary)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Connect Glasses", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Automatically connect to glasses on app startup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Switch(
                    checked = autoConnectGlasses,
                    onCheckedChange = { viewModel.setAutoConnectGlasses(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = PortaPrimary)
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "HUD Layout & Position",
                        style = MaterialTheme.typography.titleMedium,
                        color = PortaPrimary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text("Screen Position", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("top" to "Top", "center" to "Center", "bottom" to "Bottom").forEach { (id, label) ->
                            val isSelected = glassesAlignment == id
                            Button(
                                onClick = { viewModel.setGlassesAlignment(id) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) PortaPrimary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Text Size", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("tiny" to "Tiny", "xsmall" to "XS", "small" to "S", "medium" to "M", "large" to "L").forEach { (id, label) ->
                            val isSelected = glassesFontSize == id
                            Button(
                                onClick = { viewModel.setGlassesFontSize(id) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) PortaPrimary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Screen Padding", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${glassesPadding} dp", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = PortaPrimary)
                    }
                    Slider(
                        value = glassesPadding.toFloat(),
                        onValueChange = { viewModel.setGlassesPadding(it.toInt()) },
                        valueRange = 8f..48f,
                        steps = 4, // 8, 16, 24, 32, 40, 48
                        colors = SliderDefaults.colors(
                            thumbColor = PortaPrimary,
                            activeTrackColor = PortaPrimary
                        )
                    )
                }
            }

            // Provider selector
            var providerExpanded by remember { mutableStateOf(false) }
            val providers = listOf(
                "usb_display" to "USB Display (Rokid)",
                "rokid_cxrl" to "Rokid CXR-L",
                "bluetooth_serial" to "Bluetooth Serial (Direct)",
                "mock" to "Mock (Development)"
            )

            Text("Provider", style = MaterialTheme.typography.titleSmall, color = PortaPrimary)

            Box {
                OutlinedButton(
                    onClick = { providerExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(glassesProviderName)
                    Spacer(Modifier.weight(1f))
                    Text("▾", fontSize = 12.sp)
                }

                DropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    providers.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.setGlassesProvider(id)
                                providerExpanded = false
                            },
                            leadingIcon = {
                                if (glassesProviderName == label) {
                                    Icon(Icons.Default.Check, null, tint = PortaSuccess)
                                }
                            }
                        )
                    }
                }
            }

            // ── Test Actions ──
            AnimatedVisibility(
                visible = glassesState == GlassesState.SCENE_ACTIVE,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Text("Test Actions", style = MaterialTheme.typography.titleSmall, color = PortaSecondary)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.testGlassesDisplay() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Tv, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test Display", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.testGlassesPhoto() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            enabled = glassesCapabilities.canCapturePhoto
                        ) {
                            Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test Photo", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    available: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(18.dp),
            tint = if (available) PortaSuccess else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (available) "✓" else "—",
            color = if (available) PortaSuccess else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            fontWeight = FontWeight.Bold
        )
    }
}
