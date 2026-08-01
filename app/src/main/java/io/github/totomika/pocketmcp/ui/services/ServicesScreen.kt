package io.github.totomika.pocketmcp.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.script.ScriptEntry
import io.github.totomika.pocketmcp.ui.common.EmptyState
import io.github.totomika.pocketmcp.ui.common.ScriptSelectionList
import io.github.totomika.pocketmcp.ui.common.StatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    onServiceClick: (String) -> Unit,
    viewModel: ServicesViewModel = viewModel(),
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val allScripts by viewModel.allScripts.collectAsStateWithLifecycle()
    val previewPort by viewModel.previewPort.collectAsStateWithLifecycle()
    val isPortLoading by viewModel.isPortLoading.collectAsStateWithLifecycle()
    val portRangeHint = viewModel.portRangeHint
    val snackbarHostState = remember { SnackbarHostState() }

    // 从详情页返回时刷新 (scriptCount 等可能已变化)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }

    // SnackbarHost 移到 Box 外层 overlay, 不再走 Scaffold 的 snackbarHost 槽位。
    // 这样 Snackbar 出现时不会触发 Scaffold 给 FAB 让位的 inset 调整, FAB 不再被顶起来。
    // 代价: Snackbar 与 FAB 视觉上会重叠 (Snackbar 在 FAB 之下), 但通常用户能从对侧看到文本。
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_services), fontWeight = FontWeight.Bold) }) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.loadAllScripts()
                        showCreateDialog = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.create_service)) },
                )
            },
        ) { padding ->
            if (summaries.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.services_empty_title),
                    subtitle = stringResource(R.string.services_empty_subtitle),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(summaries, key = { it.service.id }) { summary ->
                        ServiceCard(
                            summary = summary,
                            onClick = { onServiceClick(summary.service.id) },
                            onToggle = {
                                if (summary.isRunning) viewModel.stopService(summary.service.id)
                                else viewModel.startService(summary.service.id)
                            },
                        )
                    }
                }
            }
        }
        // Snackbar 贴底居中, 不参与 Scaffold 布局, 不顶起 FAB
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showCreateDialog) {
        CreateServiceDialog(
            availableScripts = allScripts,
            previewPort = previewPort,
            isPortLoading = isPortLoading,
            portRangeHint = portRangeHint,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, port, selectedNamespaces ->
                viewModel.createService(name, port, selectedNamespaces) { id ->
                    if (id != null) {
                        showCreateDialog = false
                        onServiceClick(id)
                    }
                }
            },
        )
    }
}

@Composable
private fun ServiceCard(
    summary: ServiceSummary,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val profile = summary.service
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                StatusDot(isRunning = summary.isRunning)
                Column {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ":${profile.port}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            summary.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (summary.isRunning) {
                OutlinedButton(onClick = onToggle) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.stop))
                }
            } else {
                Button(onClick = onToggle) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.start))
                }
            }
        }
    }
}

@Composable
private fun CreateServiceDialog(
    availableScripts: List<ScriptEntry>,
    previewPort: Int?,
    isPortLoading: Boolean,
    portRangeHint: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, port: Int?, selectedNamespaces: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    val port = portText.toIntOrNull()

    // 选中脚本的内存状态: namespace -> true/false
    val selectedMap = remember { mutableStateMapOf<String, Boolean>() }
    val selectedNamespaces = selectedMap.filterValues { it }.keys.toList()

    // 创建按钮可用条件: 服务名非空 && (用户填了端口 || 有可用预览端口)
    val canCreate = name.isNotBlank() && (port != null || previewPort != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_service_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.create_service_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    label = { Text(stringResource(R.string.port_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 端口状态行: 加载中 / 就绪 (将分配 xxxx) / 池已满
                PortPreviewStatus(
                    previewPort = previewPort,
                    isLoading = isPortLoading,
                    portRangeHint = portRangeHint,
                )

                if (availableScripts.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.service_select_scripts, selectedNamespaces.size, availableScripts.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 限制高度, 脚本过多时内部滚动
                    ScriptSelectionList(
                        scripts = availableScripts,
                        selectedNamespaces = selectedMap.filterValues { it }.keys,
                        onToggle = { ns, checked ->
                            if (checked) selectedMap[ns] = true else selectedMap.remove(ns)
                        },
                        modifier = Modifier.heightIn(max = 220.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), port ?: previewPort, selectedNamespaces) },
                enabled = canCreate,
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 端口预览状态行: 三态显示 (加载中 / 就绪 / 默认池已满)。
 *
 * - 加载中: 灰点 + "正在寻找可用端口..." (用 italic 区分进行中状态)
 * - 已就绪: 绿点 + "将分配可用端口: $previewPort"
 * - 池已满: 红点 + "默认池 $portRangeHint 已满, 请手动指定"
 *
 * 由 [ServicesViewModel.previewPort] 与 [ServicesViewModel.isPortLoading] 驱动。
 */
@Composable
private fun PortPreviewStatus(
    previewPort: Int?,
    isLoading: Boolean,
    portRangeHint: String,
) {
    val (dotColor, text) = when {
        isLoading -> MaterialTheme.colorScheme.outline to stringResource(R.string.port_finding)
        previewPort != null -> Color(0xFF4CAF50) to stringResource(R.string.port_available, previewPort)
        else -> Color(0xFFE53935) to stringResource(R.string.port_pool_full, portRangeHint)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
