package io.github.totomika.pocketmcp.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.script.ScriptEntry

/**
 * 单个脚本的多选 Checkbox 行 (供 [ScriptSelectionDialog] 与新建服务弹窗复用)。
 *
 * @param script 脚本条目
 * @param selected 当前是否选中
 * @param onToggle 选中状态变更回调
 */
@Composable
fun ScriptSelectionRow(
    script: ScriptEntry,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点击切换选中状态; Checkbox 另有 onCheckedChange, 点击 Checkbox 时
            // 会先回调 onCheckedChange, 再冒泡到 clickable — 这里让 Row 的 clickable
            // 检查当前 selected 切换, 与 Checkbox 行为等价。
            .clickable { onToggle(!selected) }
            .padding(vertical = 2.dp),
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = onToggle,
        )
        Column {
            Text(
                script.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${script.namespace} · v${script.version}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 脚本多选列表 (Checkbox 行集合)。
 *
 * @param scripts 可选脚本
 * @param selectedNamespaces 已选中的 namespace 集合
 * @param onToggle namespace 选中状态变更
 */
@Composable
fun ScriptSelectionList(
    scripts: List<ScriptEntry>,
    selectedNamespaces: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (scripts.isEmpty()) {
        Text(stringResource(R.string.script_selection_empty), style = MaterialTheme.typography.bodyMedium)
    } else {
        Column(modifier = modifier.verticalScroll(rememberScrollState())) {
            scripts.forEach { script ->
                ScriptSelectionRow(
                    script = script,
                    selected = script.namespace in selectedNamespaces,
                    onToggle = { checked -> onToggle(script.namespace, checked) },
                )
            }
        }
    }
}

/**
 * 脚本多选弹窗 (用于向已存在的服务添加脚本)。
 *
 * 复用入口: ServiceDetailScreen "添加脚本到此服务" 与其它批量选择场景。
 *
 * @param availableScripts 可添加的脚本列表 (调用方已过滤掉已添加项)
 * @param title 弹窗标题
 * @param confirmLabel 确认按钮文案
 * @param onDismiss 取消回调
 * @param onConfirm 确认回调, 返回选中的 namespace 列表
 */
@Composable
fun ScriptSelectionDialog(
    availableScripts: List<ScriptEntry>,
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (availableScripts.isEmpty()) {
                Text(stringResource(R.string.script_selection_add_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                // 必须给滚动容器一个有限高度上限, 否则 AlertDialog 的 text 区是无限高度父容器,
                // Column(verticalScroll) 会在测量阶段抛 IllegalStateException。
                ScriptSelectionList(
                    scripts = availableScripts,
                    selectedNamespaces = selected.toSet(),
                    onToggle = { ns, checked ->
                        if (checked) selected.add(ns) else selected.remove(ns)
                    },
                    modifier = Modifier.heightIn(max = 320.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}