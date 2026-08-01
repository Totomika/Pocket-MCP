package io.github.totomika.pocketmcp.ui.scripts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.permission.PermissionDisplay
import io.github.totomika.pocketmcp.script.ScriptManager
import io.github.totomika.pocketmcp.ui.common.PillBadge
import io.github.totomika.pocketmcp.ui.common.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScriptScreen(
    onBack: () -> Unit,
    onImported: () -> Unit,
    viewModel: AddScriptViewModel = viewModel(),
) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val pendingUpdate by viewModel.pendingUpdate.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showPasteDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var aiPromptText by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFromFile(uri)
    }

    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        when (r) {
            is ScriptManager.ImportResult.Imported -> {
                snackbarHostState.showSnackbar(context.getString(R.string.import_success, r.entry.name))
                onImported()
            }

            is ScriptManager.ImportResult.SameVersion -> snackbarHostState.showSnackbar(context.getString(R.string.import_same_version))
            is ScriptManager.ImportResult.UpdateAvailable -> snackbarHostState.showSnackbar(context.getString(R.string.import_new_version, r.newVersion))
            is ScriptManager.ImportResult.OlderVersion -> snackbarHostState.showSnackbar(context.getString(R.string.import_older_version))
            is ScriptManager.ImportResult.Error -> snackbarHostState.showSnackbar(context.getString(R.string.import_failed, r.message))
        }
        viewModel.clearResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_script_title), fontWeight = FontWeight.Bold) },
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
            Spacer(Modifier.height(8.dp))

            // ── Import methods ──
            SectionHeader(title = stringResource(R.string.import_method_section))

            ImportMethodCard(
                icon = Icons.Filled.ContentPaste,
                title = stringResource(R.string.import_paste_code),
                subtitle = stringResource(R.string.import_paste_subtitle),
                onClick = { showPasteDialog = true },
                onLongClick = {
                    val clip = readClipboard(context)
                    if (clip.isNullOrBlank()) {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.clipboard_empty)) }
                    } else {
                        viewModel.importFromPaste(clip)
                    }
                },
            )

            ImportMethodCard(
                icon = Icons.Filled.Link,
                title = stringResource(R.string.import_from_url),
                subtitle = stringResource(R.string.import_url_subtitle),
                onClick = { showUrlDialog = true },
            )

            ImportMethodCard(
                icon = Icons.Filled.FileOpen,
                title = stringResource(R.string.import_from_file),
                subtitle = stringResource(R.string.import_file_subtitle),
                onClick = { filePicker.launch(arrayOf("text/*", "application/javascript")) },
            )

            // ── AI prompt ──
            SectionHeader(title = stringResource(R.string.ai_generate_section), trailing = {
                PillBadge(text = stringResource(R.string.copy_prompt))
            })
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.ai_header),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        stringResource(R.string.ai_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = aiPromptText,
                        onValueChange = { aiPromptText = it },
                        label = { Text(stringResource(R.string.requirement_description)) },
                        placeholder = { Text(stringResource(R.string.ai_prompt_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    val context = LocalContext.current
                    Button(
                        onClick = {
                            val fullPrompt = buildAiPrompt(aiPromptText)
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "MCP Script Prompt",
                                    fullPrompt
                                )
                            )
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.prompt_copied)) }
                        },
                        enabled = aiPromptText.isNotBlank() && !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.copy_prompt_to_clipboard)) }
                }
            }

            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Paste dialog ──
    if (showPasteDialog) {
        var pasteCode by remember { mutableStateOf("") }
        val dialogContext = LocalContext.current
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text(stringResource(R.string.import_paste_code)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                val clip = readClipboard(dialogContext)
                                if (clip.isNullOrBlank()) {
                                    scope.launch { snackbarHostState.showSnackbar(dialogContext.getString(R.string.clipboard_empty)) }
                                } else {
                                    pasteCode = clip
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.height(18.dp)
                            )
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text(stringResource(R.string.get_from_clipboard))
                        }
                    }
                    OutlinedTextField(
                        value = pasteCode,
                        onValueChange = { pasteCode = it },
                        label = { Text(stringResource(R.string.script_code)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 8,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importFromPaste(pasteCode); showPasteDialog = false },
                    enabled = pasteCode.isNotBlank() && !loading,
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // ── URL dialog ──
    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.import_from_url)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.script_url)) },
                    placeholder = { Text(stringResource(R.string.script_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.importFromUrl(url); showUrlDialog = false },
                    enabled = url.isNotBlank() && !loading,
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // ── Update confirmation dialog ──
    val pending = pendingUpdate
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelUpdate() },
            title = { Text(stringResource(R.string.update_script_title)) },
            text = {
                Column {
                    Text("${pending.existingVersion} -> ${pending.newVersion}")
                    if (pending.newPermissions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.new_permissions), style = MaterialTheme.typography.labelMedium)
                        pending.newPermissions.forEach { decl ->
                            val display = PermissionDisplay.from(decl)
                            Text(
                                "  ${display.icon} ${stringResource(display.descriptionRes, *display.descriptionArgs)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmUpdate() }) { Text(stringResource(R.string.confirm_update)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelUpdate() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImportMethodCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun buildAiPrompt(userNeed: String): String {
    return """你是一位资深 MCP (Model Context Protocol) 脚本工程师。下面是一份**完整的、自包含的**规范 —— 即便你从未见过本项目, 仅凭本文档就能生成可运行的脚本。请严格按规范生成**单个 JavaScript 脚本文件**, 满足用户需求。

运行环境: Android 应用内嵌 QuickJS 引擎, 脚本以 "MCP 工具" 的形式被外部 LLM 客户端调用。脚本不负责网络监听/进程管理, 只需声明工具 + 实现 handler。

═══════════════════════════════════════════
## 1. 脚本结构 (绝对必须)
═══════════════════════════════════════════

脚本由两部分组成, 顺序固定:
1. 头部元数据注释 (`// @field value` 格式)
2. 顶层 `mcp.tool(...)` 注册调用 (可多个)

`mcp` 和 `host` 是运行环境预注入的全局对象, **不要重新声明** (`var host = {}` / `const mcp = ...` 都会覆盖注入)。不要包到 IIFE 里, 不要写 `import`/`export`/`require`。脚本顶层代码在加载时执行一次 (用于注册工具), 之后只在工具被调用时执行 handler。

```js
// @name 显示名 (人类可读, 简洁)
// @namespace 唯一标识, 正则 ^[a-z][a-z0-9-]*${'$'} (如 memory, calc-todo)
// @version 语义版本号 x.y.z (如 1.0.0)
// @description 给用户看的简介 (App UI 展示)
// @instructions 可选, 给连接到此 MCP 的 AI 客户端的使用说明 (可多行, 续行用 //   )
// @author 可选
// @homepage 可选
// @minAppVersion 可选, 最低兼容 App 版本
// @permission 权限声明, 每行一条, 仅声明实际用到的 (见 §3)

mcp.tool("tool_name", "给 AI 看的工具说明", {
  // JSON Schema (见 §5)
}, async (args) => {
  // handler 实现 (见 §4 / §6)
  return { content: [{ type: "text", text: "结果" }] };
});
```

═══════════════════════════════════════════
## 2. 运行环境约束 (重要)
═══════════════════════════════════════════

- **QuickJS, 非 Node / 非浏览器**: 无 `require` / `import` / `process` / `Buffer` / 原生 `fetch` / `XMLHttpRequest` / DOM。可用全局: `mcp`, `host`, 以及 QuickJS 内置 (`JSON`, `Math`, `Date`, `Promise`, `Array`, `Object`, `Uint8Array`, `TextDecoder`? 无 — 没有 TextEncoder/TextDecoder)。
- **内存上限 16 MB, 栈 512 KB**。避免在内存里堆积大数组/大字符串; 大数据用 `host.fs` 落盘或 `host.sql` 存储。
- **工具调用超时**: 默认 30 秒 (含其中所有 `await` 的 IO)。脚本可为单个工具声明更长/更短超时: `mcp.tool(name, desc, schema, handler, { timeoutMs: 120000 })`, 取值 1000~180000 ms, 默认 30000。超时后该次调用返回错误; **只有死循环 / 同步卡死才会让整个运行时 "中毒"** (后续所有调用失败、需在 App 重启服务恢复), **单纯的 I/O 慢 (fetch / 文件) 超时不会中毒** —— 运行时仍存活, 客户端可重试。注意: `timeoutMs` 只为慢 IO 争取时间; **长时间同步 CPU 计算 (无 `await`) 即使声明了长超时, 仍会因阻塞 dispatcher 导致中毒**。所以: 绝不写死循环或长时间同步计算; 合法的慢 IO 工具直接声明足够长的 `timeoutMs`, 不要硬扛默认 30s。
- **调用完全串行, 无并发**: 同一脚本的多个工具调用排队、一次只跑一个。一个慢工具会阻塞所有排队中的调用; 若它死循环 / 卡死导致中毒, 排在后面的也全部失败。不要加锁, 也不要假设有并发; 想要吞吐就把每个 handler 写快。
- **定时器**: 没有"后台事件循环" —— 每次工具调用的执行会**贪婪等待其中启动的所有异步活儿干完**才返回。
  - `host.setTimeout` 的回调**会在本次工具调用期间触发** (调用会等它)。handler 内要延迟用 `await new Promise(r => host.setTimeout(r, ms))`; 不 `await` 的 fire-and-forget `setTimeout` 也会拖住本次调用等它跑完。
  - **不要用 `host.setInterval`**: 它每次触发都重排下一次, 会让"等所有异步干完"永远等不到 -> 本次执行永不返回。顶层用会挂死脚本加载 (运行时起不来); handler 内用会卡死那次调用 -> 30s 超时中毒。要循环就在 handler 内用 `setTimeout` 手动递归并 `await`, 且务必留退出条件。
- **数据持久性**: `host.kv` / `host.sql` / `host.fs.private` / `host.fs.external` 的数据在脚本运行时销毁后仍保留, 下次加载可继续读。`host.fs.shared` 直接操作设备外部存储, 数据由脚本自己管理。

═══════════════════════════════════════════
## 3. 权限声明 (@permission)
═══════════════════════════════════════════

未声明的权限在运行时调用对应 API 会被 `SecurityException` 拒绝。
**自动授予 (无需声明)**: `host.fs.private` / `host.fs.external` / `host.kv` / `host.sql` / `host.console` / `host.crypto` / `host.setTimeout` 等。

| Token | 说明 | 需要 spec? |
|------|------|---|
| `host.fs.shared.read:<glob>` | 读取共享文件系统。`~` = 设备外部存储根; `**` 递归含自身; `*` 单层匹配 | 是 |
| `host.fs.shared.write:<glob>` | 写共享文件系统, **隐含 read** | 是 |
| `host.fetch` | 网络请求 | 否 |
| `host.clipboard` | 剪贴板读写 | 否 |
| `host.deviceInfo` | 设备信息查询 | 否 |
| `host.toast` | Android Toast 提示 | 否 |
| `host.openUrl` | 打开 URL / Intent | 否 |

glob 示例: `~/Documents/**` (Documents 目录及其所有子项), `~/Documents/notes/*` (notes 下直接子项), `~/Download/a.txt` (单文件)。
**仅声明实际用到的路径范围** —— App UI 会向用户展示权限范围, 过宽 (如 `~/**`) 会被用户拒绝。

```
// @permission host.fs.shared.read:~/Documents/**
// @permission host.fs.shared.write:~/Documents/notes/*
// @permission host.fetch
```

═══════════════════════════════════════════
## 4. 注册工具 — mcp.tool(name, description, inputSchema, handler, options?)
═══════════════════════════════════════════

- `name`: 工具本地名, 正则 `^[a-zA-Z0-9_-]+${'$'}`, **禁止含点**。MCP 客户端看到的全名是 `namespace.name`。
- `description`: 给 AI 调用方看的说明, 写清 "做什么 + 输入 + 输出", 不要写代码实现细节。
- `inputSchema`: JSON Schema 对象 (见 §5)。
- `handler`: `async (args) => result`。`args` 是按 schema 解析后的对象, 字段对应 `properties` 的键。
  - 成功: `return { content: [{ type: "text", text: "..." }], isError: false }`
  - 失败: `return { content: [{ type: "text", text: "错误说明" }], isError: true }`
  - **不要 throw 出 handler**。用 `try/catch` 捕获 `host.*` 异常, 转 `isError: true`, 文本里给原因 + 可选修复建议。
  - 目前 `content` 仅支持 `type: "text"` (`image` / `resource` 暂未实现)。
- `options` (可选): `{ timeoutMs?: number }` —— 该工具单次调用超时 (ms), 取值 1000~180000, 默认 30000。合法的慢 IO 工具 (大 fetch、批量文件处理) 声明足够长的超时, 避免被默认 30s 误杀。注意: 仅死循环 / 同步卡死会中毒运行时, 慢 IO 超时不会 (见 §2); 但长时间同步 CPU 计算 (无 `await`) 即使声明了长 `timeoutMs` 仍会中毒。

═══════════════════════════════════════════
## 5. inputSchema 约束
═══════════════════════════════════════════

- 必须是 `type: "object"` 且含 `properties`。
- **不要**使用 `${'$'}ref` / `${'$'}defs` / `${'$'}schema` / `additionalProperties` / `oneOf` / `anyOf` 等高级特性 —— schema 原样透传给 LLM, 不做解析, 用了也无效且会让 LLM 困惑。
- 嵌套对象、数组 (`type: "array"`, `items`) 均可。
- `required` 是字符串数组, 字段名应与 `properties` 键一致。
- 每个字段配 `description`, 帮助 LLM 正确填参。

═══════════════════════════════════════════
## 6. host.* API 完整参考 (按实际实现校对)
═══════════════════════════════════════════

所有方法挂在全局 `host` 对象上。**涉及 IO 的方法返回 Promise, 用 `await`**。

### 6.1 基础能力 (无需权限)

```js
// 日志, 写入 App 内日志面板
host.console.log(...args);   // 也支持 .info / .warn / .error

// 计时器 (同步返回 id; 回调异步执行)
const id = host.setTimeout(callback, ms);
host.clearTimeout(id);
const id2 = host.setInterval(callback, ms);  // ⚠️ 实际不可用, 见 §2: 会挂死所在执行
host.clearInterval(id2);

// 加密 / 随机数 / 编解码 / 摘要 (同步)
host.crypto.randomUUID();                      // -> string (UUID v4)
host.crypto.getRandomValues(uint8ArrayInstance); // 就地填充并返回同一数组
host.crypto.b64encode("hello");                // -> "aGVsbG8=" (UTF-8 字符串 -> base64)
host.crypto.b64decode("aGVsbG8=");             // -> Uint8Array
host.crypto.md5("hello");                      // -> hex 字符串
host.crypto.sha1("hello");                     // -> hex 字符串
host.crypto.sha256("hello");                   // -> hex 字符串
```

### 6.2 键值存储 host.kv (自动授予, per-namespace 隔离)

**适合**: 简单字符串配置/状态/小记忆。值只能是字符串, 复杂数据请用 `host.sql`。

```js
await host.kv.set(key, value);          // value 必须是 string
const v = await host.kv.get(key);       // -> string | null
await host.kv.delete(key);
const keys = await host.kv.list();      // -> string[] (已自动 JSON.parse)
await host.kv.clear();
```

### 6.3 关系数据库 host.sql (自动授予, per-namespace SQLite, WAL 模式)

**适合**: 结构化数据、需要查询/索引/事务的场景。

```js
const db = await host.sql.open("mydb");       // 同名重复调用复用连接
await db.exec(sql, argsArray?);         // argsArray 绑定 ? 占位符
const rows = JSON.parse(await db.query(sql, argsArray?));  // 返回 JSON 字符串, 需手动 parse
// rows = [{ col1: ..., col2: ... }, ...]
await db.execMany([sql1, sql2, ...]);   // 单事务批量执行
await db.transaction(async (tx) => {    // tx 只有 .exec(sql, args) 和 .query(sql, args)
  await tx.exec("INSERT INTO t VALUES (?, ?)", [a, b]);
  // throw 任一错误 -> 自动 ROLLBACK
});
await db.close();                       // 显式释放, 或 runtime 销毁时框架自动清理
await host.sql.drop("mydb");           // 删除整个数据库文件 (含 WAL/SHM 旁路文件)
```

⚠️ **已知限制**: 绑定参数当前会被 `toString()` 强转, 数字/布尔会变字符串, `null` 会变空串 `""`。若需严格 SQL NULL 或整数类型, 用 SQL 字面量或在 `exec` 前自行转换。`query` 返回的 BLOB 列当前是无用字符串, 避免存取 BLOB。

### 6.4 文件系统 host.fs (三个命名空间, 方法名一致)

方法: `read` / `readBytes` / `write` / `append` / `exists` / `mkdir` / `readdir` / `stat` / `delete` / `rename` / `lines`

| 命名空间 | 路径形态 | 权限 | 隔离 |
|---|---|---|---|
| `private` | 相对沙箱根 (app 私有, 用户不可见) | 自动授予 | per-namespace |
| `external` | 相对沙箱根 (Android/data, 用户可见) | 自动授予 | per-namespace |
| `shared` | `~/...` (设备外部存储根) 或 `/abs/path` | 需 §3 权限 | 无沙箱, 仅 glob 限制 |

```js
const text = await host.fs.private.read("notes/log.txt");   // -> string (UTF-8), 单次 ≤ 8 MiB
// 超过 8 MiB 的文件: 用 force=true 强制读取 (需确保 memoryLimit 足够), 或用 lines() 流式分页
const big = await host.fs.private.read("big.json", true);   // force=true, 跳过大小检查
// 范围读取 (按字节): read(path, force, offset, length) — 从第 offset 字节起读 length 字节
// 适用: 大文件局部预览 / 分块处理; offset 越界返回空串, length 超过剩余字节自动截断
// 注意: offset/length 是字节单位, 若切到多字节 UTF-8 字符中间, 边界处会出 U+FFFD 替换符
const chunk = await host.fs.private.read("big.json", false, 0, 4096);    // 前 4 KiB
const tail  = await host.fs.private.read("big.json", false, 1024);       // 从 1 KiB 处读到 EOF
const bytes = await host.fs.private.readBytes("bin/data");   // -> Uint8Array, 单文件 ≤ 16 MiB
await host.fs.private.write("notes/log.txt", "内容");       // 覆盖写
await host.fs.private.append("notes/log.txt", "更多");      // 追加
const ok = await host.fs.private.exists("notes/log.txt");   // -> boolean
const made = await host.fs.private.mkdir("notes/sub");      // -> boolean (是否新建成功)
const entries = JSON.parse(await host.fs.private.readdir("notes")); // -> string[], 单目录 ≤ 10000 条
const stat = JSON.parse(await host.fs.private.stat("notes/log.txt"));
// stat = { size:number, isFile:boolean, isDir:boolean, mtime:number }
// 不存在会抛 IllegalStateException, 先 exists 判断或 try/catch
const removed = await host.fs.private.delete("notes/old");  // -> boolean, 递归删除
await host.fs.private.rename("a.txt", "b.txt");

// lines(path) — async generator, 流式逐行遍历大文件 (有状态迭代器, O(n))
// 适合超过 read 8 MiB 限制的大文件; finally 自动关闭文件句柄, break 也不泄漏
for await (const line of host.fs.private.lines("biglog.txt")) {
  if (line.includes("ERROR")) host.console.log(line);
}
// host.fs.external / host.fs.shared 方法名相同, 路径相对各自沙箱根
// host.fs.shared 路径须以 ~ 或 / 开头:
await host.fs.shared.read("~/Documents/notes.txt");          // ~ = 设备外部存储根
await host.fs.shared.write("~/Documents/log.txt", text);     // 需 write 权限 (隐含 read)
await host.fs.shared.rename("~/a.txt", "~/b.txt");           // 两端都需 write 权限
// shared 相对路径 (如 "foo/bar") 会被 IllegalArgumentException 拒绝
```

### 6.5 网络 host.fetch (需 `@permission host.fetch`)

```js
const resp = await host.fetch(url, opts?);
// opts = { method?: string, headers?: object, body?: string }
// resp = { status:number, ok:boolean, headers:object, _body:string, text(), json() }
const text = resp.text();             // 同步返回 string (不是 Promise; await 也能用)
const data = resp.json();             // 同步返回已 parse 的对象
```

- 仅允许 `http://` / `https://`, 30 秒超时, 自动跟随重定向。
- `method` 默认 `GET`; 非 GET/HEAD 时 `Content-Type` 默认 `application/json` (可在 `headers` 覆盖)。
- `body` 只接受 string, 传对象需 `JSON.stringify(obj)`。
- 响应整体载入内存 (无流式); 同名响应头多值只保留最后一个。

### 6.6 系统能力 host.system (各自需独立权限)

```js
const text = await host.system.clipboard.get();   // 需 host.clipboard
await host.system.clipboard.set(text);
const info = await host.system.deviceInfo();      // 需 host.deviceInfo
// info = { model, androidVersion, sdkVersion, manufacturer, screen:{width,height,density} }
host.system.toast("message");                     // 需 host.toast; 同步, 无返回
await host.system.openUrl("https://...");         // 需 host.openUrl
```

═══════════════════════════════════════════
## 7. 完整示例 (SQL + FS 组合)
═══════════════════════════════════════════

```js
// @name Notes
// @namespace notes
// @version 1.0.0
// @description 带全文搜索的笔记工具, SQLite 存储 + 可导出 JSON 文件
// @instructions 用 notes.add 新建笔记, notes.search 搜索, notes.export 导出

mcp.tool("add", "Add a note with title and content", {
  type: "object",
  properties: {
    title: { type: "string", description: "Note title" },
    content: { type: "string", description: "Note body text" }
  },
  required: ["title", "content"]
}, async (args) => {
  try {
    const db = await host.sql.open("notes");
    await db.exec("CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, title TEXT, content TEXT, ts TEXT)");
    const id = host.crypto.randomUUID();
    const ts = String(Date.now());
    await db.exec("INSERT INTO notes (id, title, content, ts) VALUES (?, ?, ?, ?)", [id, args.title, args.content, ts]);
    return { content: [{ type: "text", text: "created: " + id }] };
  } catch (e) {
    return { content: [{ type: "text", text: "add failed: " + (e.message || e) }], isError: true };
  }
});

mcp.tool("search", "Search notes by keyword in title or content", {
  type: "object",
  properties: {
    keyword: { type: "string", description: "Search keyword (substring match)" }
  },
  required: ["keyword"]
}, async (args) => {
  try {
    const db = await host.sql.open("notes");
    const like = "%" + args.keyword + "%";
    const rows = JSON.parse(await db.query(
      "SELECT id, title, ts FROM notes WHERE title LIKE ? OR content LIKE ? ORDER BY ts DESC LIMIT 50",
      [like, like]
    ));
    if (rows.length === 0) return { content: [{ type: "text", text: "(no matches)" }] };
    const list = rows.map(r => r.title + " [" + new Date(Number(r.ts)).toISOString() + "]").join("\n");
    return { content: [{ type: "text", text: list }] };
  } catch (e) {
    return { content: [{ type: "text", text: "search failed: " + (e.message || e) }], isError: true };
  }
});

mcp.tool("export", "Export all notes to private storage as JSON file", {
  type: "object",
  properties: {}
}, async () => {
  try {
    const db = await host.sql.open("notes");
    const rows = JSON.parse(await db.query("SELECT id, title, content, ts FROM notes ORDER BY ts ASC"));
    await host.fs.private.write("export.json", JSON.stringify(rows));
    return { content: [{ type: "text", text: "exported " + rows.length + " notes to export.json" }] };
  } catch (e) {
    return { content: [{ type: "text", text: "export failed: " + (e.message || e) }], isError: true };
  }
});
```

═══════════════════════════════════════════
## 8. 编写要求 (务必遵守)
═══════════════════════════════════════════

1. **只输出一个完整脚本文件**, 用 ```js ... ``` 包裹, 头部注释写在代码块最上方。
2. 工具数量按需求合理: 一个工具做一件事; 不要堆砌 10 个工具, 也不要一个工具包揽所有。
3. `description` 写语义不写实现 (面向 AI 调用方)。
4. `inputSchema` 每个字段配 `description`, 用标准 JSON Schema 字段。
5. handler **不要吞异常**: `try/catch` 转 `isError: true`, 文本说明原因 + 可选修复建议。
6. 用到网络/共享文件/剪贴板等时, 显式声明对应 `@permission`。
7. 不要用 ESM `import`/`export`, 不要 `require`, 不要假设有 Node 或浏览器 API。
8. 不要在 handler 里写死循环或超长同步计算 (30s 超时会中毒运行时, 后续调用全部失败)。
9. 大数据优先落盘 (`host.fs` / `host.sql`), 内存里只留当前需要处理的部分。
10. 若用户需求模糊, 选合理默认, 在 `@description` 末尾备注 "如需 X 可调整 Y"。
11. 不要在脚本中提及本规范本身或内部实现细节。
12. `mcp` 和 `host` 是预注入全局, 不要 `var host = {}` 或 `const mcp = ...`。

═══════════════════════════════════════════
## 用户需求
$userNeed
"""
}

/**
 * 读取系统剪贴板文本。
 *
 * @return 剪贴板中的文本, 若为空或非文本类型则返回 null。
 */
private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip: ClipData = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}
