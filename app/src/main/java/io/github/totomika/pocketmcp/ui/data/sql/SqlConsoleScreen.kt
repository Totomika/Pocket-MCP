package io.github.totomika.pocketmcp.ui.data.sql

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.data.sql.RowData
import androidx.compose.ui.res.stringResource

private val quickTemplates = listOf(
    "SELECT * FROM sqlite_master WHERE type='table'",
    "SELECT name FROM sqlite_master WHERE type='table'",
    "PRAGMA table_info(table_name)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqlConsoleScreen(
    namespace: String,
    dbName: String,
    onBack: () -> Unit,
    viewModel: SqlConsoleViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.sql_console_title), fontWeight = FontWeight.Bold)
                        Text(
                            "[$namespace] › $dbName",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Editor section (fixed at top, not scrollable) ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // SQL editor with inline toolbar
                OutlinedTextField(
                    value = uiState.sql,
                    onValueChange = { viewModel.setSql(it) },
                    placeholder = { Text(stringResource(R.string.sql_console_input_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 200.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    minLines = 3,
                    maxLines = 8,
                    trailingIcon = {
                        if (uiState.sql.isNotBlank()) {
                            IconButton(onClick = { viewModel.clearSql() }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                )

                // Inline toolbar: execute button + history toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { viewModel.run(namespace, dbName) },
                        enabled = !uiState.isExecuting && uiState.sql.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (uiState.isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sql_execute))
                    }
                    if (uiState.history.isNotEmpty()) {
                        FilterChip(
                            selected = showHistory,
                            onClick = { showHistory = !showHistory },
                            leadingIcon = {
                                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = { Text(stringResource(R.string.history)) },
                        )
                    }
                }
            }

            // ── History section (collapsible, vertical) ──
            if (showHistory && uiState.history.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.history) { sql ->
                        val label = sql.take(50) + if (sql.length > 50) "…" else ""
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.applyHistory(sql); showHistory = false },
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(12.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            // ── Result section (fills remaining space, independently scrollable) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (val result = uiState.result) {
                    is ConsoleResultState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }

                    is ConsoleResultState.Select -> {
                        if (result.rows.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.sql_console_empty_result),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            SelectResultTable(
                                columns = result.columns,
                                rows = result.rows,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    is ConsoleResultState.Update -> {
                        // Prominent banner with icon
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(20.dp),
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    stringResource(R.string.sql_console_affected_rows, result.affectedRows),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }

                    is ConsoleResultState.Error -> {
                        // Prominent error banner with icon
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        stringResource(R.string.sql_console_exec_error),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        result.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }

                    is ConsoleResultState.Idle -> {
                        // Helpful idle state with quick-start templates
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Filled.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.sql_console_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.sql_console_idle_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(20.dp))
                            // Quick-start templates
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                quickTemplates.forEach { template ->
                                    TextButton(
                                        onClick = { viewModel.setSql(template) },
                                    ) {
                                        Text(
                                            template,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectResultTable(
    columns: List<String>,
    rows: List<RowData>,
    modifier: Modifier = Modifier,
) {
    var cellDialogContent by remember { mutableStateOf<String?>(null) }
    var cellDialogTitle by remember { mutableStateOf("") }

    val hScroll = rememberScrollState()

    Column(modifier = modifier) {
        // Result count header
        Text(
            stringResource(R.string.sql_console_result_count, rows.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll),
        ) {
            Column {
                // Header row (sticky-like, just at top)
                ResultHeaderRow(columns = columns)
                // Data rows using LazyColumn for performance
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows) { row ->
                        ResultDataRow(
                            columns = columns,
                            row = row,
                            onCellClick = { colName, text ->
                                cellDialogTitle = colName
                                cellDialogContent = text
                            },
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    // Cell viewer using ModalBottomSheet
    cellDialogContent?.let { content ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = {
                cellDialogContent = null
            },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    cellDialogTitle,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            content,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { cellDialogContent = null }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeaderRow(columns: List<String>) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { col ->
            Box(
                modifier = Modifier
                    .widthIn(min = 120.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = col,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ResultDataRow(
    columns: List<String>,
    row: RowData,
    onCellClick: (colName: String, text: String) -> Unit,
) {
    Row(
        modifier = Modifier.heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { col ->
            val value = row[col]
            val text = cellText(value)
            Box(
                modifier = Modifier
                    .widthIn(min = 120.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onCellClick(col, text) },
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                when (value) {
                    null -> NullChip()
                    is ByteArray -> BlobCell()
                    else -> Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

