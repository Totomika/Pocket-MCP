package io.github.totomika.pocketmcp.ui.scripts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.script.ScriptEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScriptListItem(
    val entry: ScriptEntry,
    val toolCount: Int,
    val permissionCount: Int,
    val grantedCount: Int,
)

class ScriptsViewModel(app: Application) : AndroidViewModel(app) {
    private val scriptManager by lazy { app.container.scriptManager }
    private val permissionManager by lazy { app.container.permissionManager }
    private val scriptManifestStore by lazy { app.container.scriptManifestStore }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _allScripts = MutableStateFlow<List<ScriptListItem>>(emptyList())

    /**
     * 脚本列表, 随 [searchQuery] 过滤。
     *
     * 原实现依赖 [ScriptRepository] 的 Room Flow React观察, 重构后元数据走文件 manifest,
     * 没有 Flow 订阅源, 改为 init 加载 + 显式 [reload]。文件 manifest 写完自然会在下次 reload 反映。
     */
    val filtered: StateFlow<List<ScriptListItem>> =
        kotlinx.coroutines.flow.combine(_allScripts, _searchQuery) { items, q ->
            val query = q.trim()
            if (query.isBlank()) items
            else items.filter {
                it.entry.name.contains(query, ignoreCase = true) ||
                        it.entry.namespace.contains(query, ignoreCase = true) ||
                        it.entry.description.contains(query, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        reload()
    }

    /** 重新从 manifest store 加载所有脚本并汇总。导入/卸载后调用。 */
    fun reload() {
        viewModelScope.launch {
            val manifests = scriptManifestStore.listAll()
            _allScripts.value = manifests.map { (ns, manifest) ->
                val perms = manifest.permissions
                val toolCount = countTools(scriptManager.readScriptCode(ns) ?: "")
                ScriptListItem(
                    entry = manifest.metadata.toScriptEntry(),
                    toolCount = toolCount,
                    permissionCount = perms.size,
                    grantedCount = perms.count { it.granted },
                )
            }
        }
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    private fun countTools(code: String): Int {
        return Regex("""mcp\.tool\(\s*["']([^"']+)["']""").findAll(code).count()
    }
}

/** ScriptManifest.Metadata → 内存读模 ScriptEntry (兼容 UI 旧代码)。 */
private fun io.github.totomika.pocketmcp.script.ScriptManifest.Metadata.toScriptEntry(): ScriptEntry =
    ScriptEntry(
        namespace = namespace,
        name = name,
        version = scriptVersion,
        description = description,
        author = author,
        instructions = instructions,
        homepage = homepage,
        minAppVersion = minAppVersion,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        importedAt = importedAt,
        updatedAt = updatedAt,
    )
