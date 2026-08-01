package io.github.totomika.pocketmcp.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.data.log.LogEntry
import io.github.totomika.pocketmcp.data.log.LogType
import io.github.totomika.pocketmcp.ui.common.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel = viewModel(),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedType by viewModel.selectedType.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    // ── Selection mode ──
    var inSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val allSelected = logs.isNotEmpty() && logs.all { it.id in selectedIds }

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            inSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_exit))
                        }
                    },
                    title = { Text(stringResource(R.string.logs_selected_count, selectedIds.size)) },
                    actions = {
                        IconButton(onClick = {
                            selectedIds =
                                if (allSelected) emptySet() else logs.map { it.id }.toSet()
                        }) {
                            Icon(
                                if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                contentDescription = if (allSelected) stringResource(R.string.selection_deselect_all) else stringResource(R.string.selection_select_all),
                            )
                        }
                        IconButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val text = logs
                                    .filter { it.id in selectedIds }
                                    .joinToString("\n---\n") { formatLogEntry(it) }
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Logs", text))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.logs_copied, selectedIds.size),
                                    Toast.LENGTH_SHORT
                                ).show()
                                inSelectionMode = false
                                selectedIds = emptySet()
                            },
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.selection_copy))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.logs_title), fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { inSelectionMode = true }) {
                            Icon(Icons.Filled.Checklist, contentDescription = stringResource(R.string.selection_mode))
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear))
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
        ) {
            // ── Search ──
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text(stringResource(R.string.logs_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )

            // ── Type filter chips ──
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { viewModel.setSelectedType(null) },
                    label = { Text(stringResource(R.string.filter_all)) },
                )
                LogType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { viewModel.setSelectedType(if (selectedType == type) null else type) },
                        label = { Text(typeLabel(type, context)) },
                    )
                }
            }

            // ── Log list ──
            if (logs.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Article,
                    title = stringResource(R.string.logs_empty_title),
                    subtitle = stringResource(R.string.logs_empty_subtitle),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(logs, key = { it.id }) { log ->
                        LogItem(
                            log = log,
                            inSelectionMode = inSelectionMode,
                            isSelected = log.id in selectedIds,
                            onClick = {
                                selectedIds = if (log.id in selectedIds) {
                                    selectedIds - log.id
                                } else {
                                    selectedIds + log.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        val currentType = selectedType
        val currentQuery = searchQuery
        val dialogMessage = when {
            currentType != null && currentQuery.isNotBlank() ->
                context.getString(R.string.logs_clear_type_query_confirm, typeLabel(currentType, context), currentQuery)

            currentType != null ->
                context.getString(R.string.logs_clear_type_confirm, typeLabel(currentType, context))

            currentQuery.isNotBlank() ->
                context.getString(R.string.logs_clear_query_confirm, currentQuery)

            else -> context.getString(R.string.logs_clear_all_confirm)
        }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearLogs(); showClearDialog = false }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LogItem(
    log: LogEntry,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
    val typeColor = when (log.type) {
        "MCP" -> MaterialTheme.colorScheme.primary
        "SYSTEM" -> MaterialTheme.colorScheme.tertiary
        "CONSOLE" -> when (log.level) {
            "ERROR" -> MaterialTheme.colorScheme.error
            "WARN" -> Color(0xFFFF9800)
            else -> MaterialTheme.colorScheme.onSurface
        }

        else -> MaterialTheme.colorScheme.onSurface
    }
    val typeBg = when (log.type) {
        "MCP" -> MaterialTheme.colorScheme.primaryContainer
        "SYSTEM" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val itemBg = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(itemBg)
            .then(
                if (isSelected) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp)
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (inSelectionMode) {
                    Modifier.combinedClickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        onClick = { focusManager.clearFocus() },
                        onLongClick = {
                            val text = formatLogEntry(log)
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Log", text))
                            Toast.makeText(context, context.getString(R.string.logs_copied_single), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            )
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inSelectionMode) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .then(
                            if (!isSelected) {
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    RoundedCornerShape(50)
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(typeBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = log.namespace,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = typeColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            color = typeColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatLogEntry(log: LogEntry): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
    val level = if (log.level.isNotBlank()) " ${log.level}" else ""
    return "[$time] [${log.namespace}] ${log.type}${level}\n${log.message}"
}

private fun typeLabel(type: LogType, context: Context): String = when (type) {
    LogType.CONSOLE -> context.getString(R.string.log_type_console)
    LogType.MCP -> context.getString(R.string.log_type_mcp)
    LogType.SYSTEM -> context.getString(R.string.log_type_system)
}
