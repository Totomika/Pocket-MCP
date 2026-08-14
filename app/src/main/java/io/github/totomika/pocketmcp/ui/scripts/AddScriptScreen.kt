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
    return """你是 Pocket MCP(Android 端 MCP 服务器)的资深脚本工程师。你的唯一任务:根据文末【用户需求】,生成一个完整的、可直接运行的 JavaScript 脚本文件。本规范自包含,无需任何项目背景知识。

════════ 0. 用户需求 ════════

【用户需求】
$userNeed

(阅读完下方全部规范后,回到这里开始生成。)

════════ 1. 运行环境 ════════

- 引擎为 Android 内嵌 QuickJS:**没有** Node / 浏览器 API(无 require / import / process / Buffer / fetch / XMLHttpRequest / DOM / TextEncoder / TextDecoder)。
- 预注入两个全局对象:`mcp`(注册工具)与 `host`(扩展能力)。**禁止重新声明**(如 `var host = {}`),禁止 IIFE 包裹,禁止 import / export / require。
- 脚本顶层代码在加载时执行一次(仅用于注册工具);工具被调用时才执行 handler。
- 内存上限默认 16 MB、栈 512 KB;调用超时默认 30s。大数据优先落盘(host.fs / host.sql)。
- 工具调用**完全串行**:同一脚本的调用排队、一次只跑一个;handler 要写得快,不要加锁。
- 数据持久:`host.kv` / `host.sql` / `host.fs.private` / `host.fs.external` 的数据在脚本销毁后仍保留。

════════ 2. 绝对禁忌(违反 = 脚本不可用) ════════

1. **死循环 / 长时间同步 CPU 计算(无 await)**:会阻塞整个运行时,后续所有调用全部失败,只能重启服务恢复。合法的慢 I/O(大 fetch / 批量文件)请声明长 `timeoutMs`,不要硬扛默认 30s。
2. **在 handler 里"等待"定时器**(如 `await new Promise(r => host.setTimeout(r, ms))`):定时器回调不会在当前工具调用内触发,该 Promise 永不 resolve,运行时会误判引擎损坏。需要延迟就基于真实 I/O(host.fetch / host.fs)实现。
3. **handler 直接 throw**:必须 try/catch 捕获,返回 `{ content: [{ type: "text", text: "原因 + 可选修复建议" }], isError: true }`。
4. **使用未声明的能力**:未在 `@permission` 声明的 API 调用会抛 SecurityException。
5. **覆盖 `mcp` / `host` 全局**,或使用 Node / 浏览器 API。
6. 不要在脚本中提及本规范或内部实现细节。

════════ 3. 脚本结构(顺序固定) ════════

1. 头部元数据注释(`// @field value` 格式,每个字段一行)
2. 顶层 `mcp.tool(...)` 注册调用(可多个)

```js
// @name 显示名 (中文, 人类可读, 简洁)
// @namespace 唯一标识, 匹配 ^[a-z][a-z0-9-]*${'$'} (如 memory, calc-todo)
// @version 语义版本号 x.y.z
// @description 给用户看的简介 (中文, App UI 展示)
// @instructions 可选, 给连接到此 MCP 的 AI 客户端的使用说明 (可多行, 续行用 //   )
// @author 可选
// @homepage 可选
// @minAppVersion 可选
// @permission 权限声明, 每行一条, 仅声明实际用到的 (见 §4)

mcp.tool("tool_name", "给 AI 调用方看的英文说明", {
  type: "object",
  properties: { /* 见 §5 */ },
  required: []
}, async (args) => {
  // handler (见 §5)
  return { content: [{ type: "text", text: "结果" }] };
});
```

════════ 4. 权限声明(@permission) ════════

**自动授予(无需声明)**:`host.fs.private` / `host.fs.external` / `host.kv` / `host.sql` / `host.console` / `host.crypto` / `host.setTimeout` 等。

**需要声明**:

| Token | 说明 |
|---|---|
| `host.fs.shared.read:<glob>` | 读共享文件系统(外部存储) |
| `host.fs.shared.write:<glob>` | 写共享文件系统,隐含 read |
| `host.fetch` | 网络请求 |
| `host.clipboard` | 剪贴板读写 |
| `host.deviceInfo` | 设备信息查询 |
| `host.toast` | Android Toast 提示 |
| `host.openUrl` | 打开 URL / Intent |

- glob 规则:`~` = 外部存储根,`**` 递归(含自身),`*` 单层匹配。例:`~/Documents/**`、`~/Download/a.txt`。
- **只声明实际用到的路径范围**,过宽(如 `~/**`)会被用户在 App 里拒绝。

════════ 5. mcp.tool API ════════

`mcp.tool(name, description, inputSchema, handler, options?)`

- `name`:匹配 `^[a-zA-Z0-9_-]+${'$'}`,**禁止含点**;客户端看到的全名是 `namespace.name`。
- `description`:面向 AI 调用方,写清"做什么 + 输入 + 输出",不写实现细节。**用英文**。
- `inputSchema`:仅用 `type: "object"` + `properties`(+ `required` / `items`)。**不要**用 `${'$'}ref` / `${'$'}defs` / `${'$'}schema` / `additionalProperties` / `oneOf` / `anyOf` —— schema 原样透传给 LLM,用了无效且添乱。每个字段配 `description`(英文)。
- `handler`:`async (args) => result`,成功 `return { content: [{ type: "text", text: "..." }] }`;失败 `isError: true`(见 §2-3)。`content` 目前仅支持 `type: "text"`。
- `options`(可选):`{ timeoutMs?: number }` —— 单次调用超时(ms),钳制到 1000~180000,默认 30000。仅为慢 I/O 争取时间;死循环 / 长同步计算即使声明长超时仍会中毒(见 §2-1)。

════════ 6. host.* API 参考(按需查阅) ════════

> 以下为完整参考,**不要全部使用**,只选用任务需要的;涉及 IO 的方法返回 Promise,必须 `await`。

**6.1 基础(无需权限)**

```js
host.console.log(...args);                 // 也支持 .info / .warn / .error,写入 App 日志面板
const id = host.setTimeout(cb, ms);        // 同步注册立即返回 id;回调在工具调用结束后触发(见 §2-2)
host.clearTimeout(id);
const id2 = host.setInterval(cb, ms);      // 固定间隔重复(下限 4ms),直到 clearInterval
host.clearInterval(id2);
host.crypto.randomUUID();                  // -> string (UUID v4)
host.crypto.getRandomValues(uint8Array);   // 就地填充并返回同一数组
host.crypto.b64encode("hello");            // UTF-8 string -> base64 string
host.crypto.b64decode("aGVsbG8=");         // -> Uint8Array
host.crypto.md5("hello");                  // -> hex string(还有 sha1 / sha256)
```

**6.2 键值存储 host.kv(自动授予,per-namespace 隔离)**

```js
await host.kv.set(key, value);   // value 必须是 string
const v = await host.kv.get(key);      // -> string | null
await host.kv.delete(key);
const keys = await host.kv.list();     // -> string[]
await host.kv.clear();
```

适合简单配置 / 状态 / 小记忆;复杂数据用 host.sql。

**6.3 关系数据库 host.sql(自动授予,per-namespace SQLite,WAL 模式)**

```js
const db = await host.sql.open("mydb");              // 同名重复调用复用连接
await db.exec(sql, argsArray?);                      // argsArray 绑定 ? 占位符
const rows = JSON.parse(await db.query(sql, argsArray?)); // 返回 JSON 字符串,需手动 parse
await db.execMany([sql1, sql2, ...]);                // 单事务批量执行
await db.transaction(async (tx) => { /* tx.exec / tx.query; throw 任一错误 -> ROLLBACK */ });
await db.close();                                    // 显式释放
await host.sql.drop("mydb");                         // 删除整个数据库文件
```

已知限制:绑定参数会被 `toString()` 强转(数字/布尔变字符串,`null` 变空串),需要严格类型请在 exec 前自行转换;BLOB 列返回无用字符串,避免存取。

**6.4 文件系统 host.fs(三个命名空间,方法名一致)**

方法:`read / readBytes / write / append / exists / mkdir / readdir / stat / delete / rename / lines`

| 命名空间 | 路径形态 | 权限 |
|---|---|---|
| `private` | 相对沙箱根(app 私有) | 自动授予 |
| `external` | 相对沙箱根(Android/data) | 自动授予 |
| `shared` | `~/...` 或 `/abs/path`(外部存储) | 需 §4 权限 |

```js
await host.fs.private.read("notes/log.txt");              // -> string (UTF-8),单次 ≤ 8 MiB
await host.fs.private.read("big.json", true);             // force=true 跳过大小检查
await host.fs.private.read("big.json", false, 0, 4096);   // 字节范围读 (offset, length);切到多字节字符中间会出 U+FFFD
await host.fs.private.readBytes("bin/data");              // -> Uint8Array,单文件 ≤ 16 MiB
await host.fs.private.write("a.txt", "内容");             // 覆盖写
await host.fs.private.append("a.txt", "更多");
const ok = await host.fs.private.exists("a.txt");         // -> boolean
await host.fs.private.mkdir("notes/sub");
JSON.parse(await host.fs.private.readdir("notes"));       // -> string[](单目录 ≤ 10000 条)
JSON.parse(await host.fs.private.stat("a.txt"));          // { size, isFile, isDir, mtime };不存在会抛错,先 exists 或 try/catch
await host.fs.private.delete("notes/old");                // 递归删除
await host.fs.private.rename("a.txt", "b.txt");
for await (const line of host.fs.private.lines("biglog.txt")) { ... } // 流式逐行,finally 自动关闭句柄
```

`external` / `shared` 方法名相同;`shared` 路径必须以 `~` 或 `/` 开头,相对路径会被拒绝。

**6.5 网络 host.fetch(需 `@permission host.fetch`)**

```js
const resp = await host.fetch(url, opts?);
// opts = { method?, headers?, body? }   body 只接受 string,传对象需 JSON.stringify
// resp = { status, ok, headers, _body, text(), json() }   text/json 同步返回
```

仅 http/https,30s 超时,自动跟随重定向;非 GET/HEAD 默认 `Content-Type: application/json`;响应整体载入内存。

**6.6 系统 host.system(各需独立权限)**

```js
await host.system.clipboard.get();       // 需 host.clipboard
await host.system.clipboard.set(text);
await host.system.deviceInfo();          // 需 host.deviceInfo -> { model, androidVersion, sdkVersion, manufacturer, screen:{width,height,density} }
host.system.toast("message");            // 需 host.toast;同步,无返回
await host.system.openUrl("https://...");// 需 host.openUrl
```

════════ 7. 参考示例(SQL + FS 组合,展示推荐风格) ════════

```js
// @name 笔记
// @namespace notes
// @version 1.0.0
// @description 带全文搜索的笔记工具,SQLite 存储 + 可导出 JSON 文件
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
    await db.exec("INSERT INTO notes (id, title, content, ts) VALUES (?, ?, ?, ?)", [id, args.title, args.content, String(Date.now())]);
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

════════ 8. 质量要求与输出格式(严格遵守) ════════

1. 工具粒度:一个工具做一件事,数量与需求匹配(通常 1~5 个);不要堆砌,也不要一个大工具包揽一切。
2. 语言:`@name` / `@description` 用中文;工具 `description` 与 schema 字段 `description` 用英文。
3. 需求模糊时选合理默认,并在 `@description` 末尾注明"如需 X 可调整 Y"。
4. 大文件 / 大数据落盘(host.fs / host.sql),内存只留当前处理的部分。
5. **只输出一个代码块**(```js ... ```),内部是完整脚本(头部元数据注释在最上方);**代码块外不要任何文字**(无解释、无前言、无总结)。
6. 输出前自查:
   - [ ] 元数据完整(name / namespace / version / description 必填),namespace 与工具名均符合正则
   - [ ] 用到的 `host.*` 能力都已声明对应 `@permission`
   - [ ] 每个 handler 都有 try/catch,失败返回 `isError: true`,无 throw
   - [ ] 无死循环 / 无长同步计算 / 无"等待定时器"
   - [ ] 无 import / export / require,无 Node / 浏览器 API,未覆盖 `mcp` / `host`

现在,请回到 §0 的用户需求,开始生成。
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
