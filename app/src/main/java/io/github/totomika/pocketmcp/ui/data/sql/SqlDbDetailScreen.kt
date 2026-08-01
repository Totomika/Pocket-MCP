package io.github.totomika.pocketmcp.ui.data.sql

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqlDbDetailScreen(
    namespace: String,
    dbName: String,
    onBack: () -> Unit,
    onNavigateToTable: (String, String, String) -> Unit,
    onNavigateToConsole: (String, String) -> Unit,
    viewModel: SqlDbDetailViewModel = viewModel(),
) {
    LaunchedEffect(namespace, dbName) { viewModel.load(namespace, dbName) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeleteDbDialog by remember { mutableStateOf(false) }
    var tableToDelete by remember { mutableStateOf<String?>(null) }
    var tableToRename by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = dbName,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "[$namespace] › $dbName · ${uiState.tables.size} tables",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToConsole(namespace, dbName) }) {
                        Icon(Icons.Filled.Terminal, contentDescription = stringResource(R.string.sql_console_title))
                    }
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sql_delete_db), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                showDeleteDbDialog = true
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (uiState.tables.isEmpty() && !uiState.loading) {
                EmptyState(
                    icon = Icons.Filled.Terminal,
                    title = stringResource(R.string.sql_db_detail_empty_tables),
                    subtitle = stringResource(R.string.sql_db_detail_empty_tables_subtitle),
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (uiState.tables.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.tables, key = { it.name }) { table ->
                        TableItem(
                            table = table,
                            onClick = { onNavigateToTable(namespace, dbName, table.name) },
                            onRename = { tableToRename = table.name },
                            onDelete = { tableToDelete = table.name },
                        )
                    }
                }
            }
        }
    }

    // 删除表确认
    tableToDelete?.let { tableName ->
        DangerousActionConfirmDialog(
            title = stringResource(R.string.sql_delete_table_title),
            objectLabel = "\"$tableName\"",
            onConfirm = {
                viewModel.deleteTable(namespace, dbName, tableName)
                tableToDelete = null
            },
            onDismiss = { tableToDelete = null },
        )
    }

    // 重命名表
    tableToRename?.let { oldName ->
        var newName by remember(oldName) { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { tableToRename = null },
            title = { Text(stringResource(R.string.sql_rename_table_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank() && newName != oldName) {
                            viewModel.renameTable(namespace, dbName, oldName, newName)
                        }
                        tableToRename = null
                    },
                    enabled = newName.isNotBlank() && newName != oldName,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { tableToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 删除数据库确认
    if (showDeleteDbDialog) {
        DangerousActionConfirmDialog(
            title = stringResource(R.string.sql_delete_db),
            objectLabel = "\"$dbName\"",
            onConfirm = {
                viewModel.deleteDb(namespace, dbName)
                showDeleteDbDialog = false
                onBack()
            },
            onDismiss = { showDeleteDbDialog = false },
        )
    }

}

@Composable
private fun TableItem(
    table: io.github.totomika.pocketmcp.data.sql.TableSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true },
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = table.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${table.columnCount} cols \u00B7 ${table.rowCount} rows",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}
