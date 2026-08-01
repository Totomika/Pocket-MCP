package io.github.totomika.pocketmcp.ui.data.sql

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.data.sql.ColumnInfo
import io.github.totomika.pocketmcp.data.sql.RowData

const val COPY_CHAR_LIMIT = 10_000
const val ROWID_KEY = "__rowid__"

/* ─── Helper functions ─── */

@Composable
fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> stringResource(R.string.format_bytes_b, bytes)
        bytes < 1024 * 1024 -> stringResource(R.string.format_bytes_kb, (bytes / 1024.0).toFloat())
        bytes < 1024 * 1024 * 1024 -> stringResource(R.string.format_bytes_mb, (bytes / (1024.0 * 1024.0)).toFloat())
        else -> stringResource(R.string.format_bytes_gb, (bytes / (1024.0 * 1024.0 * 1024.0)).toFloat())
    }
}

fun formatDouble(value: Double): String {
    val s = value.toString()
    return if (s.contains(".")) s.trimEnd('0').trimEnd('.') else s
}

@Composable
fun ByteArray.toHexDump(): String {
    val emptyLabel = stringResource(R.string.hex_dump_empty)
    val truncatedLabel = stringResource(R.string.hex_dump_truncated, size - 1024)
    val limit = minOf(size, 1024)
    if (limit == 0) return emptyLabel
    val sb = StringBuilder()
    for (i in 0 until limit step 16) {
        sb.append(String.format("%04X: ", i))
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (j in 0 until 16) {
            val idx = i + j
            if (idx < limit) {
                val b = this[idx].toInt() and 0xFF
                hex.append(String.format("%02X ", b))
                ascii.append(if (b in 32..126) b.toChar() else '.')
            } else {
                hex.append("   ")
                ascii.append(' ')
            }
        }
        sb.append(hex.toString().trimEnd())
        sb.append("  |")
        sb.append(ascii.toString())
        sb.appendLine("|")
    }
    if (size > 1024) {
        sb.appendLine(truncatedLabel)
    }
    return sb.toString()
}

fun cellDisplayText(value: Any?): String = when (value) {
    null -> "NULL"
    is ByteArray -> "(BLOB)"
    is Double -> formatDouble(value)
    else -> value.toString()
}

fun cellText(value: Any?): String = when (value) {
    null -> "NULL"
    is ByteArray -> "BLOB(${value.size})"
    else -> value.toString()
}

fun rowToCopyText(row: RowData, columns: List<ColumnInfo>, truncatedLabel: String): String {
    val sb = StringBuilder()
    for (col in columns) {
        val value = row[col.name]
        val display = when (value) {
            null -> "NULL"
            is ByteArray -> "BLOB(${value.size} bytes)"
            is Double -> formatDouble(value)
            else -> value.toString()
        }
        sb.append(col.name).append(": ").append(display).append('\n')
        if (sb.length > COPY_CHAR_LIMIT) {
            sb.setLength(COPY_CHAR_LIMIT)
            sb.append("\n" + truncatedLabel)
            return sb.toString()
        }
    }
    return sb.toString().trimEnd()
}

fun rowPreviewText(row: RowData, columns: List<ColumnInfo>, maxCols: Int = 3): String {
    val sb = StringBuilder()
    var count = 0
    for (col in columns) {
        if (col.name == ROWID_KEY) continue
        if (count >= maxCols) break
        val value = row[col.name]
        val display = cellDisplayText(value)
        if (sb.isNotEmpty()) sb.append(", ")
        sb.append("${col.name}=$display")
        count++
    }
    val result = sb.toString()
    return if (result.length > 60) result.take(60) + "…" else result
}

/* ─── Dialogs & Sheets ─── */

@Composable
fun DangerousActionConfirmDialog(
    title: String,
    objectLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dangerous_delete_confirm, objectLabel))
                Text(
                    stringResource(R.string.dangerous_delete_warning_irreversible),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.dangerous_delete_warning_script_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellViewerBottomSheet(
    col: ColumnInfo,
    value: Any?,
    row: RowData,
    onCopy: (String) -> Unit,
    onUpdate: (Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val isBlob = value is ByteArray
    val canEdit = !isBlob && !col.isAutoIncrement

    var editMode by remember(col, value, row) { mutableStateOf(false) }
    var editValue by remember(col, value, row) { mutableStateOf("") }
    var editIsNull by remember(col, value, row) { mutableStateOf(false) }

    val displayText = when (value) {
        null -> stringResource(R.string.sql_null)
        is ByteArray -> value.toHexDump()
        is Double -> formatDouble(value)
        else -> value.toString()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(col.name, fontWeight = FontWeight.Bold)
                    if (col.type.isNotBlank()) {
                        Text(
                            col.type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onCopy(displayText) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.copy),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Content
            if (editMode) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isText = !col.isInteger && !col.isReal
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { editValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !editIsNull,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                col.isInteger -> KeyboardType.Number
                                col.isReal -> KeyboardType.Decimal
                                else -> KeyboardType.Text
                            },
                        ),
                        singleLine = !isText,
                        maxLines = if (isText) 5 else 1,
                    )
                    if (!col.isNotNull) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = editIsNull,
                                onCheckedChange = { editIsNull = it },
                            )
                            Text(stringResource(R.string.sql_set_null), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = displayText,
                            fontFamily = if (isBlob) FontFamily.Monospace else FontFamily.Default,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (editMode) {
                    TextButton(onClick = {
                        val newValue: Any? = if (editIsNull) null else when {
                            col.isInteger -> editValue.toLongOrNull() ?: 0L
                            col.isReal -> editValue.toDoubleOrNull() ?: 0.0
                            else -> editValue
                        }
                        onUpdate(mapOf(col.name to newValue))
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.save))
                    }
                    TextButton(onClick = { editMode = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    if (canEdit) {
                        TextButton(onClick = {
                            editValue = when (value) {
                                null -> ""
                                is Double -> formatDouble(value)
                                is ByteArray -> ""
                                else -> value.toString()
                            }
                            editIsNull = value == null
                            editMode = true
                        }) {
                            Text(stringResource(R.string.edit))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionBottomSheet(
    rowPreview: String,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    canEdit: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.row_selected_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = rowPreview,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            HorizontalDivider()

            // Copy
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCopy(); onDismiss() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(stringResource(R.string.row_copy))
            }
            HorizontalDivider()

            // Edit
            if (canEdit) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(); onDismiss() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(stringResource(R.string.row_edit))
                }
                HorizontalDivider()
            }

            // Delete
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDelete(); onDismiss() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.row_delete), color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowEditorSheet(
    title: String,
    columns: List<ColumnInfo>,
    initialRow: RowData?,
    onDismiss: () -> Unit,
    onSave: (Map<String, Any?>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    val fieldValues = remember(columns, initialRow) {
        mutableStateMapOf<String, String>().apply {
            columns.forEach { col ->
                val value = initialRow?.get(col.name)
                this[col.name] = when (value) {
                    null -> ""
                    is ByteArray -> ""
                    is Double -> formatDouble(value)
                    else -> value.toString()
                }
            }
        }
    }
    val isNull = remember(columns, initialRow) {
        mutableStateMapOf<String, Boolean>().apply {
            columns.forEach { col ->
                this[col.name] = if (!col.isNotNull) {
                    initialRow?.get(col.name) == null
                } else {
                    false
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                columns.forEach { col ->
                    val disabled = col.isAutoIncrement || (isNull[col.name] == true)
                    if (col.isBlob) {
                        OutlinedTextField(
                            value = stringResource(R.string.sql_blob_not_editable),
                            onValueChange = {},
                            label = { Text(col.name) },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            enabled = false,
                        )
                    } else {
                        val isText = !col.isInteger && !col.isReal
                        OutlinedTextField(
                            value = fieldValues[col.name] ?: "",
                            onValueChange = { fieldValues[col.name] = it },
                            label = { Text(col.name) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !disabled,
                            readOnly = col.isAutoIncrement,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = when {
                                    col.isInteger -> KeyboardType.Number
                                    col.isReal -> KeyboardType.Decimal
                                    else -> KeyboardType.Text
                                }
                            ),
                            singleLine = !isText,
                            maxLines = if (isText) 5 else 1,
                        )
                        if (!col.isNotNull && !col.isAutoIncrement) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isNull[col.name] == true,
                                    onCheckedChange = { checked ->
                                        isNull[col.name] = checked
                                    },
                                )
                                Text(
                                    stringResource(R.string.sql_set_null),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    val updates = mutableMapOf<String, Any?>()
                    columns.forEach { col ->
                        if (col.isAutoIncrement || col.isBlob) return@forEach
                        if (isNull[col.name] == true) {
                            updates[col.name] = null
                        } else {
                            val text = fieldValues[col.name] ?: ""
                            when {
                                col.isInteger -> updates[col.name] = text.toLongOrNull() ?: 0L
                                col.isReal -> updates[col.name] = text.toDoubleOrNull() ?: 0.0
                                else -> updates[col.name] = text
                            }
                        }
                    }
                    onSave(updates)
                }) {
                    Text(stringResource(R.string.save))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

/* ─── Small cell renderers ─── */

@Composable
fun NullChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.sql_null),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BlobCell() {
    Text(
        text = stringResource(R.string.sql_blob),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun TextCell(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
