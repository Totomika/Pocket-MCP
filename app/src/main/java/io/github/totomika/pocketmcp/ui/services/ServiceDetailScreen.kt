package io.github.totomika.pocketmcp.ui.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.ui.common.ScriptSelectionDialog
import io.github.totomika.pocketmcp.ui.common.SectionHeader
import io.github.totomika.pocketmcp.ui.common.StatusDot
import io.github.totomika.pocketmcp.ui.common.generateQrCode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceDetailScreen(
    serviceId: String,
    onBack: () -> Unit,
    viewModel: ServiceDetailViewModel = viewModel(),
) {
    LaunchedEffect(serviceId) { viewModel.load(serviceId) }

    val profile by viewModel.service.collectAsStateWithLifecycle()
    val scriptDetails by viewModel.scriptDetails.collectAsStateWithLifecycle()
    val availableScripts by viewModel.availableScripts.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showQrDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var showAddScriptDialog by remember { mutableStateOf(false) }
    var showEditConfigDialog by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf("") }
    var exportFormat by remember { mutableStateOf(ExportFormat.GENERIC) }

    // 预先取出 onClick 内需要的字符串 (stringResource 仅能在 @Composable 上下文中调用)
    val copiedToClipboardMsg = stringResource(R.string.copied_to_clipboard)
    val configCopiedMsg = stringResource(R.string.config_copied)

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: stringResource(R.string.nav_services), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val p = profile
            if (p != null) {
                Spacer(Modifier.height(8.dp))

                // ── Status ──
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusDot(isRunning = isRunning)
                        Column {
                            Text(
                                if (isRunning) stringResource(R.string.running) else stringResource(R.string.status_stopped),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                ":${p.port}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ── Connection ──
                SectionHeader(title = stringResource(R.string.connection_section))
                val url = "http://127.0.0.1:${p.port}/mcp"
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                url,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showQrDialog = true }) {
                                Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.show_qr_code))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("MCP URL", url))
                                scope.launch { snackbarHostState.showSnackbar(copiedToClipboardMsg) }
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Text(stringResource(R.string.copy))
                            }
                            OutlinedButton(onClick = {
                                viewModel.generateExportConfig(exportFormat) {
                                    exportJson = it
                                    showExportDialog = true
                                }
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Upload, contentDescription = null)
                                Text(stringResource(R.string.export))
                            }
                        }
                    }
                }

                // ── Scripts ──
                SectionHeader(
                    title = stringResource(R.string.service_scripts_count, scriptDetails.size),
                    trailing = {
                        TextButton(onClick = {
                            viewModel.loadAvailableScripts(serviceId)
                            showAddScriptDialog = true
                        }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.add))
                        }
                    },
                )
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (scriptDetails.isEmpty()) {
                            Text(
                                stringResource(R.string.no_scripts_attached),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            scriptDetails.forEach { sd ->
                                ScriptItem(
                                    detail = sd,
                                    onToggle = { enabled ->
                                        viewModel.toggleScript(serviceId, sd.namespace, enabled)
                                    },
                                    onRemove = {
                                        viewModel.removeScript(serviceId, sd.namespace)
                                    },
                                )
                            }
                        }
                    }
                }

                // ── Config ──
                SectionHeader(
                    title = stringResource(R.string.config_section),
                    trailing = {
                        TextButton(onClick = { showEditConfigDialog = true }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.modify))
                        }
                    },
                )
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.port),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(60.dp)
                            )
                            Text(
                                "${p.port}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.service_name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(60.dp)
                            )
                            Text(p.name, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(60.dp)
                            )
                            Text(
                                if (p.autoCreated) stringResource(R.string.auto_created) else stringResource(R.string.custom),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // ── Actions ──
                Spacer(Modifier.height(8.dp))
                if (isRunning) {
                    Button(
                        onClick = { viewModel.stop(serviceId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text(stringResource(R.string.stop_service))
                    }
                } else {
                    Button(
                        onClick = { viewModel.start(serviceId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.start_service))
                    }
                }
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(stringResource(R.string.delete_service))
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── QR dialog ──
    if (showQrDialog) {
        val p = profile
        if (p != null) {
            val url = "http://127.0.0.1:${p.port}/mcp"
            val bitmap = remember(url) { generateQrCode(url) }
            AlertDialog(
                onDismissRequest = { showQrDialog = false },
                title = { Text(stringResource(R.string.qr_code_title)) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(240.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { showQrDialog = false }) { Text(stringResource(R.string.close)) } },
            )
        }
    }

    // ── Export dialog ──
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.export_config_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.export_format_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = exportFormat == fmt,
                                onClick = {
                                    exportFormat = fmt
                                    viewModel.generateExportConfig(fmt) { exportJson = it }
                                },
                                label = { Text(stringResource(fmt.displayNameRes)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(exportFormat.hintRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.export_instructions),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        exportJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MCP Config", exportJson))
                    scope.launch { snackbarHostState.showSnackbar(configCopiedMsg) }
                    showExportDialog = false
                }) { Text(stringResource(R.string.copy)) }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    // ── Delete dialog ──
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_service)) },
            text = { Text(stringResource(R.string.delete_service_confirm, profile?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleting = true
                        viewModel.deleteService(serviceId) {
                            showDeleteDialog = false
                            onBack()
                        }
                    },
                    enabled = !isDeleting,
                ) {
                    Text(if (isDeleting) stringResource(R.string.deleting) else stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isDeleting
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ── Add script dialog ──
    if (showAddScriptDialog) {
        ScriptSelectionDialog(
            availableScripts = availableScripts,
            title = stringResource(R.string.add_script_to_service_title),
            confirmLabel = stringResource(R.string.add),
            onDismiss = { showAddScriptDialog = false },
            onConfirm = { namespaces ->
                viewModel.addScripts(serviceId, namespaces)
                showAddScriptDialog = false
            },
        )
    }

    // ── Edit config dialog ──
    if (showEditConfigDialog) {
        val p = profile
        if (p != null) {
            EditConfigDialog(
                currentName = p.name,
                currentPort = p.port,
                isRunning = isRunning,
                onDismiss = { showEditConfigDialog = false },
                onConfirm = { name, port ->
                    viewModel.updateService(serviceId, name, port)
                    showEditConfigDialog = false
                },
            )
        }
    }
}

@Composable
private fun ScriptItem(
    detail: ScriptDetail,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Checkbox(
            checked = detail.enabled,
            onCheckedChange = onToggle,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    detail.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "v${detail.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                detail.namespace,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.tools.isNotEmpty()) {
                Text(
                    detail.tools.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EditConfigDialog(
    currentName: String,
    currentPort: Int,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, port: Int) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    var portText by remember { mutableStateOf(currentPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_config_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.service_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isRunning) {
                    Text(
                        stringResource(R.string.edit_port_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), portText.toIntOrNull() ?: currentPort) },
                enabled = name.isNotBlank() && portText.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
