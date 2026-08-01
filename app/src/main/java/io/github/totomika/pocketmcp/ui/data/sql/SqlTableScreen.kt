package io.github.totomika.pocketmcp.ui.data.sql

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.data.sql.ColumnInfo
import io.github.totomika.pocketmcp.data.sql.RowData
import io.github.totomika.pocketmcp.data.sql.SortDir
import io.github.totomika.pocketmcp.ui.common.EmptyState

private val MIN_CELL_WIDTH = 56.dp
private val MAX_CELL_WIDTH = 240.dp
private val ROW_NUMBER_WIDTH = 40.dp
private const val MEASURE_CHAR_CAP = 100
/**
 * 单元格内容弹窗状态。
 */
private data class CellDialogState(
    val col: ColumnInfo,
    val value: Any?,
    val row: RowData,
)

/**
 * 根据当前页数据和表头文本，计算每列的自适应宽度。
 *
 * 策略：
 * - 表头：测量列名宽度。
 * - 数据：遍历所有行，逐条测量展示文本像素宽度（截断到 [MEASURE_CHAR_CAP]），取最大值。
 * - NullChip：测量 "NULL" 文本宽度 + 12dp 内 padding，确保占位符不换行。
 * - 最终取三者最大值 + 24dp cell padding，clamp 到 [MIN_CELL_WIDTH]..[MAX_CELL_WIDTH]。
 *
 * 逐行测量而非只测最长字符串：因为比例字体下等长文本也可能宽度不同（如 "111" vs "888"）。
 * [MEASURE_CHAR_CAP] 截断保证超长文本不会产生高成本测量。
 */
@Composable
private fun computeColumnWidths(
    columns: List<ColumnInfo>,
    rows: List<RowData>,
    textMeasurer: TextMeasurer,
): List<Dp> {
    val labelStyle = MaterialTheme.typography.labelSmall
    val bodyStyle = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current

    return remember(columns, rows, textMeasurer, labelStyle, bodyStyle, density) {
        // NullChip 固定开销：文本 + 12dp 内 padding
        val nullChipWidthDp = density.run {
            textMeasurer.measure(text = "NULL", style = labelStyle).size.width.toDp()
        } + 12.dp

        columns.map { col ->
            // 表头宽度
            val headerWidthDp = density.run {
                textMeasurer.measure(text = col.name, style = labelStyle).size.width.toDp()
            }

            // 数据宽度：遍历所有行，逐条测量取最大值
            var maxDataWidthDp = 0.dp
            for (row in rows) {
                val text = cellDisplayText(row[col.name]).take(MEASURE_CHAR_CAP)
                val widthDp = density.run {
                    textMeasurer.measure(text = text, style = bodyStyle).size.width.toDp()
                }
                if (widthDp > maxDataWidthDp) maxDataWidthDp = widthDp
            }

            val widestDp = maxOf(headerWidthDp, nullChipWidthDp, maxDataWidthDp)
            // 24dp cell horizontal padding (12dp each side)
            (widestDp + 24.dp).coerceIn(MIN_CELL_WIDTH, MAX_CELL_WIDTH)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqlTableScreen(
    namespace: String,
    dbName: String,
    tableName: String,
    onBack: () -> Unit,
    viewModel: SqlTableViewModel = viewModel(),
) {
    LaunchedEffect(namespace, dbName, tableName) {
        viewModel.load(namespace, dbName, tableName)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val textMeasurer = rememberTextMeasurer()
    val clipboardManager = LocalClipboardManager.current

    var copyMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyMessage) {
        copyMessage?.let {
            snackbarHostState.showSnackbar(it)
            copyMessage = null
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<RowData?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var rowToDelete by remember { mutableStateOf<RowData?>(null) }
    var cellDialogState by remember { mutableStateOf<CellDialogState?>(null) }
    var rowActionState by remember { mutableStateOf<RowData?>(null) }
    var rowActionPreview by remember { mutableStateOf("") }
    val truncatedLabel = stringResource(R.string.truncated)
    val copiedLabel = stringResource(R.string.copied)

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
                            tableName,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "[$namespace] › $dbName · ${uiState.totalCount} rows",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.row_add))
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

            if (uiState.rows.isEmpty() && !uiState.loading) {
                EmptyState(
                    icon = Icons.Filled.Inbox,
                    title = stringResource(R.string.data_empty),
                    subtitle = stringResource(R.string.sql_table_empty_subtitle),
                )
            } else {
                // 计算自适应列宽
                val columnWidths = computeColumnWidths(uiState.columns, uiState.rows, textMeasurer)

                // Horizontally scrollable table
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Column(modifier = Modifier.fillMaxHeight()) {
                        HeaderRow(
                            columns = uiState.columns,
                            columnWidths = columnWidths,
                            sortCol = uiState.sortCol,
                            sortDir = uiState.sortDir,
                            onSort = { viewModel.setSort(it) },
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            itemsIndexed(uiState.rows) { index, row ->
                                DataRow(
                                    row = row,
                                    columns = uiState.columns,
                                    columnWidths = columnWidths,
                                    rowNumber = uiState.page * uiState.pageSize + index + 1,
                                    onCellClick = { col, value ->
                                        cellDialogState = CellDialogState(col, value, row)
                                    },
                                    onLongPress = {
                                        rowActionState = row
                                        rowActionPreview = rowPreviewText(row, uiState.columns)
                                    },
                                )
                            }
                        }
                    }
                }

                if (uiState.totalPages > 0) {
                    PaginationBar(
                        page = uiState.page,
                        totalPages = uiState.totalPages,
                        totalCount = uiState.totalCount,
                        onFirst = { viewModel.loadPage(0) },
                        onPrev = { viewModel.prevPage() },
                        onNext = { viewModel.nextPage() },
                        onLast = { viewModel.loadPage(uiState.totalPages - 1) },
                        onJumpTo = { page -> viewModel.loadPage(page) },
                    )
                }
            }
        }
    }

    // ── Dialogs ──

    if (showAddDialog) {
        RowEditorSheet(
            title = stringResource(R.string.row_add),
            columns = uiState.columns,
            initialRow = null,
            onDismiss = { showAddDialog = false },
            onSave = { values ->
                viewModel.insertRow(values)
                showAddDialog = false
            },
        )
    }

    if (showEditDialog) {
        editingRow?.let { row ->
            RowEditorSheet(
                title = stringResource(R.string.row_edit),
                columns = uiState.columns,
                initialRow = row,
                onDismiss = {
                    showEditDialog = false
                    editingRow = null
                },
                onSave = { values ->
                    viewModel.updateRow(row, values)
                    showEditDialog = false
                    editingRow = null
                },
            )
        }
    }

    if (showDeleteDialog) {
        rowToDelete?.let { row ->
            val preview = rowPreviewText(row, uiState.columns)
            DangerousActionConfirmDialog(
                title = stringResource(R.string.row_delete),
                objectLabel = preview,
                onConfirm = {
                    viewModel.deleteRow(row)
                    showDeleteDialog = false
                    rowToDelete = null
                },
                onDismiss = {
                    showDeleteDialog = false
                    rowToDelete = null
                },
            )
        }
    }

    // ── Cell viewer bottom sheet ──
    cellDialogState?.let { state ->
        CellViewerBottomSheet(
            col = state.col,
            value = state.value,
            row = state.row,
            onCopy = { text ->
                clipboardManager.setText(AnnotatedString(text))
                copyMessage = copiedLabel
            },
            onUpdate = { updates ->
                viewModel.updateRow(state.row, updates)
            },
            onDismiss = { cellDialogState = null },
        )
    }

    // ── Row action bottom sheet ──
    rowActionState?.let { row ->
        RowActionBottomSheet(
            rowPreview = rowActionPreview,
            onCopy = {
                clipboardManager.setText(AnnotatedString(rowToCopyText(row, uiState.columns, truncatedLabel)))
                copyMessage = copiedLabel
            },
            onEdit = {
                editingRow = row
                showEditDialog = true
            },
            onDelete = {
                rowToDelete = row
                showDeleteDialog = true
            },
            onDismiss = { rowActionState = null },
        )
    }
}

@Composable
private fun HeaderRow(
    columns: List<ColumnInfo>,
    columnWidths: List<Dp>,
    sortCol: String?,
    sortDir: SortDir,
    onSort: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 行号列表头
        Box(
            modifier = Modifier
                .width(ROW_NUMBER_WIDTH)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.sql_row_number_header),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        columns.forEachIndexed { index, col ->
            val isSorted = sortCol == col.name
            val sortable = !col.isBlob
            Box(
                modifier = Modifier
                    .width(columnWidths.getOrElse(index) { MIN_CELL_WIDTH })
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .then(
                        if (sortable) Modifier.clickable { onSort(col.name) } else Modifier
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            text = col.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSorted) FontWeight.Bold else FontWeight.Normal,
                            color = if (col.isBlob) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (col.type.isNotBlank()) {
                            Text(
                                text = col.type,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                    if (isSorted && sortDir != SortDir.NONE) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (sortDir == SortDir.ASC) {
                                Icons.Filled.KeyboardArrowUp
                            } else {
                                Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DataRow(
    row: RowData,
    columns: List<ColumnInfo>,
    columnWidths: List<Dp>,
    rowNumber: Int,
    onCellClick: (ColumnInfo, Any?) -> Unit,
    onLongPress: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = onLongPress,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Row number with subtle visual hint (slightly different background to indicate it's interactive)
            Box(
                modifier = Modifier
                    .width(ROW_NUMBER_WIDTH)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rowNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            columns.forEachIndexed { index, col ->
                val value = row[col.name]
                Box(
                    modifier = Modifier
                        .width(columnWidths.getOrElse(index) { MIN_CELL_WIDTH })
                        .clickable { onCellClick(col, value) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    when (value) {
                        null -> NullChip()
                        is ByteArray -> BlobCell()
                        is String -> TextCell(text = value)
                        is Long, is Int, is Float -> Text(
                            text = value.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        is Double -> Text(
                            text = formatDouble(value),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        else -> Text(
                            text = value.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun PaginationBar(
    page: Int,
    totalPages: Int,
    totalCount: Long,
    onFirst: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLast: () -> Unit,
    onJumpTo: (Int) -> Unit,
) {
    var showJumpDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: first + prev
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFirst, enabled = page > 0) {
                Icon(Icons.Filled.FirstPage, contentDescription = stringResource(R.string.pagination_first))
            }
            IconButton(onClick = onPrev, enabled = page > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.pagination_prev))
            }
        }

        // Center: page info + jump
        TextButton(onClick = { showJumpDialog = true }) {
            Text(stringResource(R.string.pagination_info, totalCount, page + 1, totalPages))
        }

        // Right: next + last
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNext, enabled = page + 1 < totalPages) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.pagination_next))
            }
            IconButton(onClick = onLast, enabled = page + 1 < totalPages) {
                Icon(Icons.Filled.LastPage, contentDescription = stringResource(R.string.pagination_last))
            }
        }
    }

    if (showJumpDialog) {
        var jumpPageText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text(stringResource(R.string.pagination_jump_title)) },
            text = {
                OutlinedTextField(
                    value = jumpPageText,
                    onValueChange = { jumpPageText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.pagination_page_label, totalPages)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = jumpPageText.toIntOrNull()
                    if (p != null && p in 1..totalPages) {
                        onJumpTo(p - 1)  // convert to 0-indexed
                    }
                    showJumpDialog = false
                }) {
                    Text(stringResource(R.string.pagination_jump))
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
