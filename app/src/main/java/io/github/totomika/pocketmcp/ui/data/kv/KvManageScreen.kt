package io.github.totomika.pocketmcp.ui.data.kv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.data.sql.SortDir
import io.github.totomika.pocketmcp.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KvManageScreen(
    namespace: String,
    onBack: () -> Unit,
    viewModel: KvManageViewModel = viewModel(),
) {
    LaunchedEffect(namespace) { viewModel.load(namespace) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingOverwrite by viewModel.pendingOverwrite.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingValue by rememberSaveable { mutableStateOf("") }
    var deletingKey by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.data_kv_title), fontWeight = FontWeight.Bold)
                        if (uiState.totalCount > 0) {
                            Text(
                                text = stringResource(R.string.kv_total_count, uiState.totalCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_all))
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
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Search + Sort ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text(stringResource(R.string.kv_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = { viewModel.toggleSortDir() }) {
                    Icon(
                        imageVector = if (uiState.sortDir == SortDir.ASC)
                            Icons.Filled.ArrowUpward
                        else
                            Icons.Filled.ArrowDownward,
                        contentDescription = if (uiState.sortDir == SortDir.ASC) stringResource(R.string.sort_asc) else stringResource(R.string.sort_desc),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Error (可清除) ──
            uiState.error?.let { errMsg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = errMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.error_dismiss),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── List or Empty ──
            if (!uiState.loading && uiState.items.isEmpty() && uiState.error == null) {
                EmptyState(
                    icon = Icons.Filled.Storage,
                    title = stringResource(R.string.kv_empty_title),
                    subtitle = stringResource(R.string.kv_empty_subtitle),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.items, key = { it.first }) { (key, value) ->
                        KvItem(
                            key = key,
                            value = value,
                            onClick = {
                                editingKey = key
                                editingValue = value
                            },
                            onDelete = {
                                deletingKey = key
                            },
                        )
                    }
                }
            }

            // ── Pagination ──
            if (uiState.totalPages > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { viewModel.setPage(uiState.page - 1) },
                        enabled = uiState.page > 0,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pagination_prev)
                        )
                    }
                    Text(
                        text = stringResource(R.string.kv_page_info, uiState.page + 1, uiState.totalPages),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(
                        onClick = { viewModel.setPage(uiState.page + 1) },
                        enabled = uiState.page < uiState.totalPages - 1,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.pagination_next)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // ── Add Dialog ──
    if (showAddDialog) {
        var newKey by rememberSaveable { mutableStateOf("") }
        var newValue by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.kv_add_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text(stringResource(R.string.kv_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = newKey.isBlank(),
                    )
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text(stringResource(R.string.kv_value)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addEntry(newKey, newValue)
                        showAddDialog = false
                    },
                    enabled = newKey.isNotBlank(),
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Edit Dialog ──
    editingKey?.let { key ->
        AlertDialog(
            onDismissRequest = {
                editingKey = null
                editingValue = ""
            },
            title = { Text(stringResource(R.string.kv_edit_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.kv_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = true,
                    )
                    OutlinedTextField(
                        value = editingValue,
                        onValueChange = { editingValue = it },
                        label = { Text(stringResource(R.string.kv_value)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 10,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateEntry(key, editingValue)
                    editingKey = null
                    editingValue = ""
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingKey = null
                    editingValue = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Delete Confirm ──
    deletingKey?.let { key ->
        AlertDialog(
            onDismissRequest = { deletingKey = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.kv_delete_confirm_msg, key)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(key)
                    deletingKey = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingKey = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Clear All Confirm ──
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_all)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.kv_clear_all_confirm_msg),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearDialog = false
                }) {
                    Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Overwrite Confirm (key 已存在时由 ViewModel 触发) ──
    pendingOverwrite?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelOverwrite() },
            title = { Text(stringResource(R.string.kv_key_exists_title)) },
            text = {
                Text(stringResource(R.string.kv_overwrite_confirm_msg, pending.key))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmOverwrite() }) {
                    Text(stringResource(R.string.overwrite), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelOverwrite() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun KvItem(
    key: String,
    value: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
