package io.github.totomika.pocketmcp.permission

import io.github.totomika.pocketmcp.script.ScriptManifestStore

/**
 * 权限管理器。
 *
 * 重构后权限不再独立存表, 而是嵌入 [io.github.totomika.pocketmcp.script.ScriptManifest.permissions]
 * (单 JSON 文件)。本类封装对该清单的权限字段读写, 对调用方隐藏存储细节。
 *
 * 流程:
 * 1. 导入脚本: [importPermissions] → 解析 `@permission`, 写入清单 granted=false
 * 2. 用户审阅: [grant] / [grantAll] / [revoke]
 * 3. 运行时: [ScriptPermissionChecker] 读清单
 * 4. 撤销: [revoke] 即时生效 (下次调用失败)
 * 5. 删除脚本: 卸载时整清单随 `scripts/<ns>/` 目录被删, 此处无需 deleteAll
 *
 * 见 docs/04-permissions.md。
 *
 * @param manifestStore 脚本清单存储
 */
class PermissionManager(
    private val manifestStore: ScriptManifestStore,
) {
    /**
     * 导入脚本权限声明。
     *
     * 解析头部 `@permission`, 写入清单 permissions 字段 (覆盖式),
     * 默认 granted=false, declared=true。已存在的项保留 granted 状态以避免重装刷状态。
     *
     * 前置条件: manifest 已存在 (由 ScriptManager.importScript 先写 manifest 再调本方法)。
     * 若 manifest 不存在会抛 NoSuchScriptException — 这是调用方契约, 不在本层兜底。
     *
     * @param scriptId 脚本 namespace
     * @param source 脚本源码
     * @return 解析到的权限声明 (供 UI 显示)。
     */
    suspend fun importPermissions(
        scriptId: String,
        source: String,
    ): List<PermissionDeclaration> {
        val declarations = PermissionParser.parse(source)
        val oldGranted = manifestStore.read(scriptId)?.permissions
            ?.filter { it.granted }
            ?.associate { permKey(it.token, it.spec) to it }
            ?: emptyMap()

        val entries = declarations.map { decl ->
            val key = permKey(decl.token.token, decl.spec)
            val old = oldGranted[key]
            PermissionEntry(
                token = decl.token.token,
                spec = decl.spec,
                granted = old?.granted == true,
                declared = true,
            )
        }
        manifestStore.update(scriptId) { it.copy(permissions = entries) }
        return declarations
    }

    /**
     * 获取脚本的所有权限 (含未授权)。
     */
    suspend fun getDeclared(scriptId: String): List<PermissionEntry> {
        return manifestStore.read(scriptId)?.permissions ?: emptyList()
    }

    /**
     * 获取脚本已授权的权限。
     */
    suspend fun getGranted(scriptId: String): List<PermissionEntry> {
        return manifestStore.read(scriptId)?.permissions?.filter { it.granted } ?: emptyList()
    }

    /**
     * 授权单个权限。若清单已有完全匹配 (token+spec) 项则只更新 granted;
     * 若不存在则插入一条 declared=false 项 (用户授权了脚本未声明的权限)。
     */
    suspend fun grant(
        scriptId: String,
        token: PermissionToken,
        spec: String? = null,
    ) {
        manifestStore.update(scriptId) { m ->
            val entries = m.permissions.toMutableList()
            val idx = entries.indexOfFirst { it.token == token.token && it.spec == spec }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(granted = true)
            } else {
                entries.add(
                    PermissionEntry(
                        token = token.token,
                        spec = spec,
                        granted = true,
                        declared = false,
                    )
                )
            }
            m.copy(permissions = entries)
        }
    }

    /**
     * 授权脚本的所有声明权限 (一键全部允许)。
     */
    suspend fun grantAll(scriptId: String) {
        manifestStore.update(scriptId) { m ->
            m.copy(permissions = m.permissions.map { it.copy(granted = true) })
        }
    }

    /**
     * 撤销单个权限。撤销即时生效: 下次受限 API 调用会抛 SecurityException。
     */
    suspend fun revoke(
        scriptId: String,
        token: PermissionToken,
        spec: String? = null,
    ) {
        manifestStore.update(scriptId) { m ->
            m.copy(permissions = m.permissions.map { p ->
                if (p.token == token.token && p.spec == spec) p.copy(granted = false) else p
            })
        }
    }

    /**
     * 撤销脚本的所有权限。
     */
    suspend fun revokeAll(scriptId: String) {
        manifestStore.update(scriptId) { m ->
            m.copy(permissions = m.permissions.map { it.copy(granted = false) })
        }
    }

    /**
     * 脚本更新时的权限同步。
     *
     * - 新增权限 → 追加, granted=false (需用户确认)
     * - 减少权限 → 从清单移除
     * - 不变 → 保留 granted 状态
     *
     * @return 新增的权限声明 (需用户确认)
     */
    suspend fun syncOnUpdate(
        scriptId: String,
        source: String,
    ): List<PermissionDeclaration> {
        val newDeclarations = PermissionParser.parse(source)
        val newKeys = newDeclarations.map { permKey(it.token.token, it.spec) }.toSet()

        val existing = manifestStore.read(scriptId)?.permissions ?: emptyList()
        // 保留仍在新声明中的项 (granted 状态沿用)
        val kept = existing.filter { permKey(it.token, it.spec) in newKeys }.toMutableList()

        val existingKeys = existing.map { permKey(it.token, it.spec) }.toSet()
        val added = mutableListOf<PermissionDeclaration>()
        for (decl in newDeclarations) {
            val key = permKey(decl.token.token, decl.spec)
            if (key !in existingKeys) {
                kept.add(
                    PermissionEntry(
                        token = decl.token.token,
                        spec = decl.spec,
                        granted = false,
                        declared = true,
                    )
                )
                added.add(decl)
            }
        }

        manifestStore.update(scriptId) { it.copy(permissions = kept) }
        return added
    }

    private fun permKey(token: String, spec: String?): String = "$token\u0000${spec ?: ""}"
}
