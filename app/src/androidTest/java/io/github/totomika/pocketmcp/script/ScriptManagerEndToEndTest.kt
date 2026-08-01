package io.github.totomika.pocketmcp.script

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.totomika.pocketmcp.data.db.AppDatabase
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.mcp.McpServerFactory
import io.github.totomika.pocketmcp.mcp.PortManager
import io.github.totomika.pocketmcp.mcp.ServiceManager
import io.github.totomika.pocketmcp.mcp.ServiceManifestStore
import io.github.totomika.pocketmcp.permission.PermissionManager
import io.github.totomika.pocketmcp.runtime.RuntimeManager
import io.github.totomika.pocketmcp.runtime.ToolBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M6 脚本管理端到端测试。
 *
 * 验证:
 * 1. 导入脚本 → 元数据存储 + 代码存储 + 权限导入
 * 2. 同 namespace 新版本 → 检测更新
 * 3. 同版本重复导入 → 提示已安装
 * 4. 卸载脚本 → 元数据/代码/权限删除
 * 5. 无效元数据 → 返回错误
 */
class ScriptManagerEndToEndTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var scriptManager: ScriptManager
    private lateinit var serviceManager: ServiceManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var pathManager: FsPathManager
    private lateinit var repository: ScriptRepository
    private lateinit var manifestStore: ScriptManifestStore

    private val testScript = """
        // @name Test Script
        // @namespace test-script
        // @version 1.0.0
        // @description A test script
        // @permission host.fetch

        mcp.tool("echo", "Echo", {
          type: "object",
          properties: { msg: { type: "string" } }
        }, async (args) => {
          return { content: [{ type: "text", text: args.msg }] };
        });
    """.trimIndent()

    private val testScriptV2 = """
        // @name Test Script
        // @namespace test-script
        // @version 1.1.0
        // @description A test script v2
        // @permission host.fetch
        // @permission host.clipboard

        mcp.tool("echo", "Echo", {
          type: "object",
          properties: { msg: { type: "string" } }
        }, async (args) => {
          return { content: [{ type: "text", text: args.msg }] };
        });
    """.trimIndent()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        pathManager = FsPathManager(context)
        manifestStore = ScriptManifestStore(pathManager)
        repository = ScriptRepository(pathManager)
        permissionManager = PermissionManager(manifestStore)

        val runtimeManager = RuntimeManager()
        val toolBridge = ToolBridge(runtimeManager)
        val factory = McpServerFactory(runtimeManager, toolBridge)
        val serviceManifestStore = ServiceManifestStore(pathManager)
        serviceManager = ServiceManager(
            manifestStore = serviceManifestStore,
            portManager = PortManager(),
            runtimeManager = runtimeManager,
            serverFactory = factory,
        )

        scriptManager = ScriptManager(
            repository = repository,
            manifestStore = manifestStore,
            serviceManifestStore = serviceManifestStore,
            permissionManager = permissionManager,
            serviceManager = serviceManager,
            logDao = db.logDao(),
        )
    }

    @After
    fun teardown() {
        runBlocking {
            serviceManager.destroyAll()
        }
        db.close()
    }

    @Test
    fun import_new_script_stores_metadata_code_permissions() = runBlocking {
        val result = scriptManager.importScript(testScript, ScriptSourceType.PASTE)
        assertTrue("Should be Imported", result is ScriptManager.ImportResult.Imported)

        // 元数据存储
        val entry = scriptManager.getScript("test-script")
        assertNotNull(entry)
        assertEquals("Test Script", entry!!.name)
        assertEquals("1.0.0", entry.version)

        // 代码存储
        val code = scriptManager.readScriptCode("test-script")
        assertNotNull(code)
        assertTrue(code!!.contains("mcp.tool"))

        // 权限导入 (默认未授权)
        val perms = permissionManager.getDeclared("test-script")
        assertEquals(1, perms.size) // host.fetch
        assertFalse(perms[0].granted)
    }

    @Test
    fun import_same_version_returns_same_version() = runBlocking {
        scriptManager.importScript(testScript, ScriptSourceType.PASTE)
        val result = scriptManager.importScript(testScript, ScriptSourceType.PASTE)
        assertTrue("Should be SameVersion", result is ScriptManager.ImportResult.SameVersion)
    }

    @Test
    fun import_newer_version_returns_update_available() = runBlocking {
        scriptManager.importScript(testScript, ScriptSourceType.PASTE)
        val result = scriptManager.importScript(testScriptV2, ScriptSourceType.PASTE)
        assertTrue(
            "Should be UpdateAvailable",
            result is ScriptManager.ImportResult.UpdateAvailable
        )

        val updateResult = result as ScriptManager.ImportResult.UpdateAvailable
        assertEquals("1.1.0", updateResult.newVersion)
        // 新增权限: host.clipboard
        assertEquals(1, updateResult.newPermissions.size)
    }

    @Test
    fun import_older_version_returns_older_version() = runBlocking {
        scriptManager.importScript(testScriptV2, ScriptSourceType.PASTE) // v1.1.0
        val result = scriptManager.importScript(testScript, ScriptSourceType.PASTE) // v1.0.0
        assertTrue("Should be OlderVersion", result is ScriptManager.ImportResult.OlderVersion)
    }

    @Test
    fun confirm_update_replaces_code_and_metadata() = runBlocking {
        scriptManager.importScript(testScript, ScriptSourceType.PASTE)
        scriptManager.confirmUpdate("test-script", testScriptV2)

        val entry = scriptManager.getScript("test-script")
        assertNotNull(entry)
        assertEquals("1.1.0", entry!!.version)
        assertEquals("A test script v2", entry.description)

        val code = scriptManager.readScriptCode("test-script")
        assertTrue(code!!.contains("1.1.0"))
    }

    @Test
    fun uninstall_removes_metadata_code_permissions() = runBlocking {
        scriptManager.importScript(testScript, ScriptSourceType.PASTE)
        assertNotNull(scriptManager.getScript("test-script"))

        scriptManager.uninstallScript("test-script", purgeData = true)

        assertNull(scriptManager.getScript("test-script"))
        assertNull(scriptManager.readScriptCode("test-script"))
        // 权限随 manifest 删除 (uninstall 会删整个 scripts/<ns>/ 目录)
        assertEquals(0, permissionManager.getDeclared("test-script").size)
    }

    @Test
    fun invalid_metadata_returns_error() = runBlocking {
        val badScript = """
            // @name Test
            // @namespace Test
            // @version 1.0.0

            mcp.tool("x", "x", {}, async () => {});
        """.trimIndent() // 缺 @description, namespace 大写

        val result = scriptManager.importScript(badScript, ScriptSourceType.PASTE)
        assertTrue("Should be Error", result is ScriptManager.ImportResult.Error)
    }
}
