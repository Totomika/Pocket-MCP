package io.github.totomika.pocketmcp.app

import android.content.Context
import androidx.room.Room
import io.github.totomika.pocketmcp.data.db.AppDatabase
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.data.kv.KvBrowserRepository
import io.github.totomika.pocketmcp.data.log.LogManager
import io.github.totomika.pocketmcp.data.sql.SqlBrowserRepository
import io.github.totomika.pocketmcp.host.HostApiRegistry
import io.github.totomika.pocketmcp.mcp.McpServerFactory
import io.github.totomika.pocketmcp.mcp.PortManager
import io.github.totomika.pocketmcp.mcp.ServiceManager
import io.github.totomika.pocketmcp.mcp.ServiceManifestStore
import io.github.totomika.pocketmcp.permission.PermissionManager
import io.github.totomika.pocketmcp.permission.ScriptPermissionChecker
import io.github.totomika.pocketmcp.runtime.RuntimeFactory
import io.github.totomika.pocketmcp.runtime.RuntimeManager
import io.github.totomika.pocketmcp.runtime.ToolBridge
import io.github.totomika.pocketmcp.script.ScriptManager
import io.github.totomika.pocketmcp.script.ScriptManifestStore
import io.github.totomika.pocketmcp.script.ScriptRepository
import io.github.totomika.pocketmcp.script.UrlImporter

/**
 * 手动依赖注入容器。
 *
 * 持有所有单例: db, runtimeManager, serviceManager, scriptManager, permissionManager, logManager,
 * + 各 manifest store。由 MCPocketApplication.onCreate 初始化, 通过 `application.container` 访问。
 *
 * 避免引入 Hilt/KSP 额外复杂度, 用手动 DI 足够。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    // region 数据库

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "pocketmcp.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    // endregion

    // region 路径管理

    val pathManager: FsPathManager by lazy { FsPathManager(appContext) }

    // endregion

    // region 文件化清单存储

    /** 脚本清单 (元数据 + 权限) 文件存储。 */
    val scriptManifestStore: ScriptManifestStore by lazy {
        ScriptManifestStore(pathManager)
    }

    /** 服务清单 (port + scripts[]) 文件存储。 */
    val serviceManifestStore: ServiceManifestStore by lazy {
        ServiceManifestStore(pathManager)
    }

    // endregion

    // region 管理器

    val logManager: LogManager by lazy { LogManager(database.logDao()) }

    val permissionManager: PermissionManager by lazy {
        PermissionManager(scriptManifestStore)
    }

    val runtimeManager: RuntimeManager by lazy {
        RuntimeManager(RuntimeFactory(hostApiRegistry = hostApiRegistry, logManager = logManager))
    }

    val hostApiRegistry: HostApiRegistry by lazy {
        val checker = createPermissionChecker()
        HostApiRegistry(
            HostApiRegistry.createDefault(
                pathManager = pathManager,
                context = appContext,
                fsPermissionChecker = checker,
                fetchPermissionChecker = checker,
                systemPermissionChecker = checker,
            )
        )
    }

    val scriptRepository: ScriptRepository by lazy {
        ScriptRepository(pathManager)
    }

    val serviceManager: ServiceManager by lazy {
        val toolBridge = ToolBridge(runtimeManager, logManager = logManager)
        val factory = McpServerFactory(runtimeManager, toolBridge)
        ServiceManager(
            manifestStore = serviceManifestStore,
            portManager = PortManager(),
            runtimeManager = runtimeManager,
            serverFactory = factory,
            codeLoader = { namespace -> scriptRepository.readScriptCode(namespace) },
            runtimeConfigLoader = { namespace ->
                scriptManifestStore.readSync(namespace)?.runtimeConfig
            },
            logManager = logManager,
        )
    }

    // ── 浏览器仓库 (脚本运行时数据浏览, 见 ui/data) ──

    val kvBrowserRepository: KvBrowserRepository by lazy { KvBrowserRepository(pathManager) }

    val sqlBrowserRepository: SqlBrowserRepository by lazy { SqlBrowserRepository(pathManager) }

    val scriptManager: ScriptManager by lazy {
        ScriptManager(
            repository = scriptRepository,
            manifestStore = scriptManifestStore,
            serviceManifestStore = serviceManifestStore,
            permissionManager = permissionManager,
            serviceManager = serviceManager,
            logDao = database.logDao(),
            urlImporter = UrlImporter(),
        )
    }

    /**
     * 创建权限检查器 (测试用)。
     */
    fun createPermissionChecker(): ScriptPermissionChecker {
        return ScriptPermissionChecker(scriptManifestStore, appContext)
    }

    // endregion
}
