package io.github.totomika.pocketmcp.ui.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.permission.PermissionToken
import io.github.totomika.pocketmcp.runtime.RuntimeFactory
import io.github.totomika.pocketmcp.script.RuntimeConfig
import io.github.totomika.pocketmcp.ui.common.CodeText
import io.github.totomika.pocketmcp.ui.common.InfoRow
import io.github.totomika.pocketmcp.ui.common.PillBadge
import io.github.totomika.pocketmcp.ui.common.SectionHeader
import io.github.totomika.pocketmcp.ui.common.StatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreen(
    namespace: String,
    onBack: () -> Unit,
    onNavigateToService: (String) -> Unit,
    onNavigateToDataManagement: (String) -> Unit,
    viewModel: ScriptDetailViewModel = viewModel(),
) {
    LaunchedEffect(namespace) { viewModel.load(namespace) }

    val script by viewModel.script.collectAsStateWithLifecycle()
    val permissionDisplays by viewModel.permissionDisplays.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val code by viewModel.code.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    val editMode by viewModel.editMode.collectAsStateWithLifecycle()
    val editCode by viewModel.editCode.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val showRestartDialog by viewModel.showRestartDialog.collectAsStateWithLifecycle()
    val isRestarting by viewModel.isRestarting.collectAsStateWithLifecycle()
    val runtimeConfig by viewModel.runtimeConfig.collectAsStateWithLifecycle()
    val memoryUsage by viewModel.memoryUsage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isUninstalling by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(script?.name ?: namespace, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    // 数据管理: 浏览 / 编辑脚本运行时数据 (KV / SQL)
                    IconButton(onClick = { onNavigateToDataManagement(namespace) }) {
                        Icon(Icons.Filled.Storage, contentDescription = stringResource(R.string.data_management_title))
                    }
                    // 高级设置: 运行时配置 (内存 / 栈)
                    IconButton(onClick = { showAdvancedSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.advanced_settings))
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
            val s = script
            if (s != null) {
                Spacer(Modifier.height(8.dp))

                // ── Header ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        s.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    PillBadge(text = "v${s.version}")
                }
                Text(
                    s.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                s.author?.let {
                    Text(
                        "by $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ── Info ──
                SectionHeader(title = stringResource(R.string.info_section))
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoRow("Namespace", s.namespace)
                        InfoRow("Version", s.version)
                        s.homepage?.let { InfoRow("Homepage", it) }
                        InfoRow(stringResource(R.string.source), s.sourceType)
                        s.sourceUrl?.let {
                            InfoRow(
                                "URL",
                                it.take(40) + if (it.length > 40) "..." else ""
                            )
                        }
                    }
                }

                // ── Tools ──
                SectionHeader(title = stringResource(R.string.script_tools_count, tools.size))
                if (tools.isEmpty()) {
                    Text(
                        stringResource(R.string.no_tools_detected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tools.forEach { tool ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Build,
                                        contentDescription = null,
                                        modifier = Modifier.height(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            "${s.namespace}_${tool.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (tool.description.isNotBlank()) {
                                            Text(
                                                tool.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Permissions ──
                SectionHeader(title = "${stringResource(R.string.permissions_section)} (${permissionDisplays.size})")
                if (permissionDisplays.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val anyUngranted = permissions.any { !it.granted }
                        if (anyUngranted) {
                            FilledTonalButton(onClick = { viewModel.grantAllPermissions() }) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.height(18.dp)
                                )
                                Text(stringResource(R.string.grant_all), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        val anyGranted = permissions.any { it.granted }
                        if (anyGranted) {
                            OutlinedButton(onClick = { viewModel.revokeAllPermissions() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = null,
                                    modifier = Modifier.height(18.dp)
                                )
                                Text(stringResource(R.string.revoke_all), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (permissionDisplays.isEmpty()) {
                    Text(
                        stringResource(R.string.no_permissions_declared),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            permissionDisplays.forEachIndexed { index, display ->
                                val perm =
                                    permissions.find { it.token == display.token.token && it.spec == display.spec }
                                val granted = perm?.granted == true
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = permissionIcon(display.token),
                                        contentDescription = null,
                                        modifier = Modifier.height(20.dp),
                                        tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(display.descriptionRes, *display.descriptionArgs),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        display.spec?.let {
                                            Text(
                                                if (display.recursive) stringResource(R.string.perm_recursive, it) else stringResource(R.string.perm_not_recursive, it),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = granted,
                                        onCheckedChange = { checked ->
                                            if (checked) viewModel.grantPermission(
                                                display.token,
                                                display.spec
                                            )
                                            else viewModel.revokePermission(
                                                display.token,
                                                display.spec
                                            )
                                        },
                                    )
                                }
                                if (index < permissionDisplays.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(start = 28.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Services ──
                SectionHeader(title = stringResource(R.string.script_services_count, services.size))
                if (services.isEmpty()) {
                    Text(
                        stringResource(R.string.not_in_any_service),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            services.forEach { ref ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToService(ref.service.id) },
                                ) {
                                    StatusDot(
                                        isRunning = ref.isRunning,
                                        color = if (ref.serviceRunning && !ref.scriptEnabled)
                                            Color(0xFFFFA726) else null,
                                    )
                                    Text(
                                        ref.service.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        ":${ref.service.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (ref.isRunning) {
                                        PillBadge(
                                            text = stringResource(R.string.running),
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    } else if (ref.serviceRunning) {
                                        PillBadge(
                                            text = stringResource(R.string.disabled),
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = ref.scriptEnabled,
                                        onCheckedChange = { enabled ->
                                            viewModel.toggleScriptEnabled(ref.service.id, namespace, enabled)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Actions ──
                SectionHeader(title = stringResource(R.string.actions_section))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editMode) {
                        Button(onClick = { viewModel.saveCode(namespace) }) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Text(stringResource(R.string.save))
                        }
                        OutlinedButton(onClick = { viewModel.toggleEditMode() }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                            Text(stringResource(R.string.cancel))
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.toggleEditMode() }) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Text(stringResource(R.string.edit_code))
                        }
                        if (s.sourceUrl != null) {
                            OutlinedButton(onClick = { viewModel.checkUpdates(namespace) }) {
                                Icon(Icons.Filled.Update, contentDescription = null)
                                Text(stringResource(R.string.check_updates))
                            }
                        }
                        OutlinedButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Text(stringResource(R.string.delete))
                        }
                    }
                }

                // ── Code ──
                SectionHeader(title = stringResource(R.string.code_section))
                if (editMode) {
                    OutlinedTextField(
                        value = editCode,
                        onValueChange = { viewModel.updateEditCode(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                } else {
                    val displayCode = code ?: ""
                    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        CodeText(
                            code = displayCode.take(3000) + if (displayCode.length > 3000) "\n\n" + stringResource(R.string.code_truncated, displayCode.length - 3000) else "",
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteDialog) {
        var purgeData by remember { mutableStateOf(true) }
        var purgeLogs by remember { mutableStateOf(false) }
        val runningServices = services.filter { it.isRunning }
        AlertDialog(
            onDismissRequest = { if (!isUninstalling) showDeleteDialog = false },
            title = { Text(stringResource(R.string.uninstall_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.script_uninstall_confirm, script?.name ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = purgeData, onCheckedChange = { purgeData = it })
                        Text(
                            stringResource(R.string.uninstall_purge_data),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = purgeLogs, onCheckedChange = { purgeLogs = it })
                        Text(stringResource(R.string.uninstall_purge_logs), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (runningServices.isNotEmpty()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            stringResource(R.string.uninstall_running_services_warning),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        runningServices.forEach { ref ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusDot(isRunning = true)
                                Text(
                                    "${ref.service.name} (:${ref.service.port})",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                stringResource(R.string.uninstall_risk_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isUninstalling = true
                        viewModel.uninstall(
                            namespace,
                            deleteData = purgeData,
                            purgeLogs = purgeLogs
                        ) {
                            showDeleteDialog = false
                            onBack()
                        }
                    },
                    enabled = !isUninstalling,
                ) {
                    Text(if (isUninstalling) stringResource(R.string.uninstalling) else stringResource(R.string.uninstall))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isUninstalling
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 编辑保存后, 若有服务正在运行该脚本, 提示重启以使新代码生效。
    // (RuntimeManager 引用计数机制下, 旧 runtime 不会自动重新 evaluate 新代码)
    if (showRestartDialog) {
        val runningServices = services.filter { it.isRunning }
        AlertDialog(
            onDismissRequest = { if (!isRestarting) viewModel.dismissRestartDialog() },
            title = { Text(stringResource(R.string.restart_service_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restart_service_msg))
                    Spacer(Modifier.height(4.dp))
                    runningServices.forEach { ref ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isRestarting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                StatusDot(isRunning = true)
                            }
                            Text(
                                "${ref.service.name} (:${ref.service.port})",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.restart_risk_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.restartRunningServices() },
                    enabled = !isRestarting,
                ) {
                    Text(if (isRestarting) stringResource(R.string.restarting) else stringResource(R.string.restart))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissRestartDialog() },
                    enabled = !isRestarting,
                ) { Text(stringResource(R.string.later)) }
            },
        )
    }

    if (showAdvancedSettings) {
        AdvancedSettingsDialog(
            currentConfig = runtimeConfig,
            memoryUsage = memoryUsage,
            onDismiss = { showAdvancedSettings = false },
            onSave = { config ->
                viewModel.saveRuntimeConfig(config)
                showAdvancedSettings = false
            },
        )
    }
}

// ── 高级设置 Dialog ──

/**
 * memoryLimit 预设挡位 (升序: 小 → 大 → 无限制)。
 * slider 位置 0 = 自定义, 1..6 = 预设挡位 (从小到大)。
 * 0L 表示无限制。
 */
private val MEM_LIMIT_PRESETS = listOf(
    8L * 1024 * 1024,      // 8 MB
    16L * 1024 * 1024,     // 16 MB (默认)
    32L * 1024 * 1024,     // 32 MB
    64L * 1024 * 1024,     // 64 MB
    128L * 1024 * 1024,    // 128 MB
    0L,                    // 无限制
)
private val MEM_LIMIT_LABELS: List<String?> = listOf("8 MB", "16 MB", "32 MB", "64 MB", "128 MB", null)

/** maxStackSize 预设挡位 (升序) */
private val STACK_PRESETS = listOf(
    256L * 1024,           // 256 KB
    512L * 1024,           // 512 KB (默认)
    1L * 1024 * 1024,      // 1 MB
    2L * 1024 * 1024,      // 2 MB
    4L * 1024 * 1024,      // 4 MB
    0L,                    // 无限制
)
private val STACK_LABELS: List<String?> = listOf("256 KB", "512 KB", "1 MB", "2 MB", "4 MB", null)

/** 自定义输入上限 (防误输入天文数字) */
private const val CUSTOM_MEM_MAX_MB = 2048L   // 2 GB
private const val CUSTOM_STACK_MAX_KB = 65536L // 64 MB

@Composable
private fun AdvancedSettingsDialog(
    currentConfig: RuntimeConfig?,
    memoryUsage: Pair<Long, Long>?,
    onDismiss: () -> Unit,
    onSave: (RuntimeConfig) -> Unit,
) {
    val currentMemLimit = currentConfig?.memoryLimit
    val currentStackSize = currentConfig?.maxStackSize

    // slider 范围: 0..6, 0 = 自定义, 1..6 = 预设挡位 (从小到大)
    var memSliderPos by remember {
        mutableStateOf(valueToSliderPos(currentMemLimit, MEM_LIMIT_PRESETS, RuntimeFactory.DEFAULT_MEMORY_LIMIT))
    }
    var stackSliderPos by remember {
        mutableStateOf(valueToSliderPos(currentStackSize, STACK_PRESETS, RuntimeFactory.DEFAULT_MAX_STACK_SIZE))
    }

    // 自定义值 (MB / KB)
    var customMemMB by remember {
        mutableStateOf(
            (currentMemLimit ?: RuntimeFactory.DEFAULT_MEMORY_LIMIT)
                .let { if (it == 0L) 16 else it / (1024 * 1024) }
                .coerceIn(1, CUSTOM_MEM_MAX_MB).toString()
        )
    }
    var customStackKB by remember {
        mutableStateOf(
            (currentStackSize ?: RuntimeFactory.DEFAULT_MAX_STACK_SIZE)
                .let { if (it == 0L) 512 else it / 1024 }
                .coerceIn(1, CUSTOM_STACK_MAX_KB).toString()
        )
    }

    // 验证自定义输入
    val customMemValid = memSliderPos != 0 || run {
        val v = customMemMB.toLongOrNull()
        v != null && v in 1..CUSTOM_MEM_MAX_MB
    }
    val customStackValid = stackSliderPos != 0 || run {
        val v = customStackKB.toLongOrNull()
        v != null && v in 1..CUSTOM_STACK_MAX_KB
    }
    val canSave = customMemValid && customStackValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.advanced_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // ── 内存限制 ──
                Text(stringResource(R.string.memory_limit), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (memSliderPos == 0) {
                        val mb = customMemMB.toLongOrNull() ?: 0
                        if (customMemValid) stringResource(R.string.custom_mem_value, mb)
                        else stringResource(R.string.custom_mem_invalid_label, CUSTOM_MEM_MAX_MB)
                    } else {
                        MEM_LIMIT_LABELS[memSliderPos - 1] ?: stringResource(R.string.unlimited)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (memSliderPos == 0 && !customMemValid)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = memSliderPos.toFloat(),
                    onValueChange = { memSliderPos = it.toInt() },
                    valueRange = 0f..6f,
                    steps = 5,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.custom), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.unlimited), style = MaterialTheme.typography.labelSmall)
                }
                if (memSliderPos == 0) {
                    OutlinedTextField(
                        value = customMemMB,
                        onValueChange = { customMemMB = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.memory_custom_label, CUSTOM_MEM_MAX_MB)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = !customMemValid,
                        supportingText = if (!customMemValid) {
                            { Text(stringResource(R.string.memory_custom_invalid, CUSTOM_MEM_MAX_MB)) }
                        } else null,
                    )
                }

                HorizontalDivider()

                // ── 栈大小 ──
                Text(stringResource(R.string.stack_size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (stackSliderPos == 0) {
                        val kb = customStackKB.toLongOrNull() ?: 0
                        if (customStackValid) stringResource(R.string.custom_stack_value, kb)
                        else stringResource(R.string.custom_stack_invalid_label, CUSTOM_STACK_MAX_KB)
                    } else {
                        STACK_LABELS[stackSliderPos - 1] ?: stringResource(R.string.unlimited)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stackSliderPos == 0 && !customStackValid)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = stackSliderPos.toFloat(),
                    onValueChange = { stackSliderPos = it.toInt() },
                    valueRange = 0f..6f,
                    steps = 5,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.custom), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.unlimited), style = MaterialTheme.typography.labelSmall)
                }
                if (stackSliderPos == 0) {
                    OutlinedTextField(
                        value = customStackKB,
                        onValueChange = { customStackKB = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.stack_custom_label, CUSTOM_STACK_MAX_KB)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = !customStackValid,
                        supportingText = if (!customStackValid) {
                            { Text(stringResource(R.string.memory_custom_invalid, CUSTOM_STACK_MAX_KB)) }
                        } else null,
                    )
                }

                // ── 内存使用 (运行中时显示) ──
                if (memoryUsage != null) {
                    HorizontalDivider()
                    val (used, limit) = memoryUsage
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "  " + stringResource(R.string.memory_usage),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    val usedMB = used / (1024.0 * 1024.0)
                    val unlimited = RuntimeFactory.isUnlimited(limit)
                    val limitMB = if (unlimited) Double.MAX_VALUE else limit / (1024.0 * 1024.0)
                    Text(
                        if (unlimited) stringResource(R.string.mem_usage_unlimited, usedMB)
                        else stringResource(R.string.mem_usage_limited, usedMB, limitMB),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!unlimited) {
                        LinearProgressIndicator(
                            progress = { (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.runtime_not_started),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val memLimit = if (memSliderPos == 0) {
                        customMemMB.toLongOrNull()?.coerceIn(1, CUSTOM_MEM_MAX_MB)?.let { it * 1024 * 1024 }
                    } else {
                        MEM_LIMIT_PRESETS[memSliderPos - 1]
                    }
                    val stackSize = if (stackSliderPos == 0) {
                        customStackKB.toLongOrNull()?.coerceIn(1, CUSTOM_STACK_MAX_KB)?.let { it * 1024 }
                    } else {
                        STACK_PRESETS[stackSliderPos - 1]
                    }
                    onSave(RuntimeConfig(memoryLimit = memLimit, maxStackSize = stackSize))
                },
                enabled = canSave,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 将配置值映射到 slider 位置。
 * 0 = 自定义, 1..N = 预设挡位 (按 [presets] 顺序)。
 * value=null 时查找 [defaultValue] 在预设中的位置; 不在预设中则回退到 0 (自定义)。
 */
private fun valueToSliderPos(value: Long?, presets: List<Long>, defaultValue: Long): Int {
    val target = value ?: defaultValue
    val idx = presets.indexOf(target)
    return if (idx >= 0) idx + 1 else 0
}

private fun permissionIcon(token: PermissionToken) = when (token) {
    PermissionToken.FS_SHARED_READ -> Icons.Filled.Folder
    PermissionToken.FS_SHARED_WRITE -> Icons.Filled.Edit
    PermissionToken.FETCH -> Icons.Filled.Cloud
    PermissionToken.CLIPBOARD -> Icons.Filled.ContentPaste
    PermissionToken.DEVICE_INFO -> Icons.Filled.Info
    PermissionToken.TOAST -> Icons.Filled.Notifications
    PermissionToken.OPEN_URL -> Icons.Filled.Link
}
