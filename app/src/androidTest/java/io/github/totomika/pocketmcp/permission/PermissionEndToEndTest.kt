package io.github.totomika.pocketmcp.permission

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.totomika.pocketmcp.data.db.AppDatabase
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.script.ScriptManifestStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M4 权限系统端到端测试。
 *
 * 验证:
 * 1. PermissionManager 导入/grant/revoke 流程
 * 2. ScriptPermissionChecker 运行时拦截
 * 3. 撤销即时生效
 *
 * 注意: fs.shared 的系统权限 (MANAGE_EXTERNAL_STORAGE) 在测试环境可能未授权,
 * 所以 fs.shared 相关测试预期抛 SecurityException (系统权限层)。
 * fetch/clipboard 等不依赖系统权限的 token 可完整测试。
 */
class PermissionEndToEndTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var manifestStore: ScriptManifestStore
    private lateinit var manager: PermissionManager
    private lateinit var checker: ScriptPermissionChecker

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val pathManager = FsPathManager(context)
        manifestStore = ScriptManifestStore(pathManager)
        manager = PermissionManager(manifestStore)
        checker = ScriptPermissionChecker(manifestStore, context)
    }

    @After
    fun teardown() {
        db.close()
    }

    // region PermissionManager

    @Test
    fun manager_import_permissions_from_source() = runBlocking {
        val source = """
            // @name Test
            // @permission host.fetch
            // @permission host.clipboard
            // @permission host.fs.shared.read:~/Download/**
        """.trimIndent()

        val declarations = manager.importPermissions("test-script", source)
        assertEquals(3, declarations.size)

        val stored = manager.getDeclared("test-script")
        assertEquals(3, stored.size)
        // 导入后默认未授权
        stored.forEach { assertTrue("All should be not granted", !it.granted) }
    }

    @Test
    fun manager_grant_and_revoke() = runBlocking {
        val source = """
            // @name Test
            // @permission host.fetch
            // @permission host.clipboard
        """.trimIndent()

        manager.importPermissions("test-script", source)

        // 授权 host.fetch
        manager.grant("test-script", PermissionToken.FETCH)
        val granted = manager.getGranted("test-script")
        assertEquals(1, granted.size)
        assertEquals(PermissionToken.FETCH.token, granted[0].token)

        // 撤销
        manager.revoke("test-script", PermissionToken.FETCH)
        val grantedAfterRevoke = manager.getGranted("test-script")
        assertEquals(0, grantedAfterRevoke.size)
    }

    @Test
    fun manager_grant_all() = runBlocking {
        val source = """
            // @name Test
            // @permission host.fetch
            // @permission host.clipboard
            // @permission host.toast
        """.trimIndent()

        manager.importPermissions("test-script", source)
        manager.grantAll("test-script")

        val granted = manager.getGranted("test-script")
        assertEquals(3, granted.size)
    }

    @Test
    fun manager_delete_all_on_script_removal() = runBlocking {
        val source = """
            // @name Test
            // @permission host.fetch
        """.trimIndent()

        manager.importPermissions("test-script", source)
        manager.grantAll("test-script")
        assertEquals(1, manager.getGranted("test-script").size)

        manifestStore.delete("test-script")
        assertEquals(0, manager.getDeclared("test-script").size)
    }

    // endregion

    // region ScriptPermissionChecker

    @Test(expected = SecurityException::class)
    fun checker_fetch_not_granted_throws() = runBlocking {
        manager.importPermissions(
            "test-script", """
            // @permission host.fetch
        """.trimIndent()
        )
        // 未 grant, 调用应抛 SecurityException
        checker.check("test-script", "https://example.com")
    }

    @Test
    fun checker_fetch_granted_passes() = runBlocking {
        manager.importPermissions(
            "test-script", """
            // @permission host.fetch
        """.trimIndent()
        )
        manager.grant("test-script", PermissionToken.FETCH)

        // 已 grant, 不应抛异常
        checker.check("test-script", "https://example.com")
    }

    @Test(expected = SecurityException::class)
    fun checker_clipboard_not_granted_throws() = runBlocking {
        manager.importPermissions(
            "test-script", """
            // @permission host.clipboard
        """.trimIndent()
        )
        checker.check("test-script", PermissionToken.CLIPBOARD)
    }

    @Test
    fun checker_clipboard_granted_passes() = runBlocking {
        manager.importPermissions(
            "test-script", """
            // @permission host.clipboard
        """.trimIndent()
        )
        manager.grant("test-script", PermissionToken.CLIPBOARD)
        checker.check("test-script", PermissionToken.CLIPBOARD)
    }

    @Test(expected = SecurityException::class)
    fun checker_revoke_takes_effect_immediately() = runBlocking {
        manager.importPermissions(
            "test-script", """
            // @permission host.fetch
        """.trimIndent()
        )
        manager.grant("test-script", PermissionToken.FETCH)

        // 验证已授权
        checker.check("test-script", "https://example.com")

        // 撤销
        manager.revoke("test-script", PermissionToken.FETCH)

        // 再次调用应抛异常 (撤销即时生效)
        checker.check("test-script", "https://example.com")
    }

    @Test(expected = SecurityException::class)
    fun checker_unknown_namespace_throws() {
        // 未导入任何权限的 namespace
        checker.check("unknown-script", PermissionToken.FETCH)
    }

    // endregion

    // region ScriptPermissionChecker — fs.shared (系统权限层)

    @Test(expected = SecurityException::class)
    fun checker_fs_shared_without_system_permission_throws() = runBlocking {
        // 测试环境通常没有 MANAGE_EXTERNAL_STORAGE
        manager.importPermissions(
            "test-script", """
            // @permission host.fs.shared.read:~/Download/**
        """.trimIndent()
        )
        manager.grant("test-script", PermissionToken.FS_SHARED_READ, "~/Download/**")

        // 即使脚本权限已 grant, 系统权限未授权也应抛 SecurityException
        checker.checkRead("test-script", "~/Download/file.txt")
    }

    // endregion

    // region PermissionManager — syncOnUpdate

    @Test
    fun manager_sync_on_update_detects_new_permissions() = runBlocking {
        // v1: 只有 host.fetch
        manager.importPermissions(
            "test-script", """
            // @permission host.fetch
        """.trimIndent()
        )
        manager.grantAll("test-script")

        // v2: 新增 host.clipboard, 保留 host.fetch
        val added = manager.syncOnUpdate(
            "test-script", """
            // @permission host.fetch
            // @permission host.clipboard
        """.trimIndent()
        )

        assertEquals(1, added.size)
        assertEquals(PermissionToken.CLIPBOARD, added[0].token)

        // host.fetch 的 granted 状态应保留
        val all = manager.getDeclared("test-script")
        assertEquals(2, all.size)
        val fetchEntry = all.find { it.token == PermissionToken.FETCH.token }
        assertTrue("host.fetch should still be granted", fetchEntry?.granted == true)

        // host.clipboard 应未授权 (新增, 需用户确认)
        val clipboardEntry = all.find { it.token == PermissionToken.CLIPBOARD.token }
        assertTrue("host.clipboard should not be granted", clipboardEntry?.granted == false)
    }

    @Test
    fun manager_sync_on_update_removes_deleted_permissions() = runBlocking {
        // v1: host.fetch + host.clipboard
        manager.importPermissions(
            "test-script", """
            // @permission host.fetch
            // @permission host.clipboard
        """.trimIndent()
        )
        manager.grantAll("test-script")

        // v2: 只保留 host.fetch (减少权限)
        manager.syncOnUpdate(
            "test-script", """
            // @permission host.fetch
        """.trimIndent()
        )

        val all = manager.getDeclared("test-script")
        assertEquals(1, all.size)
        assertEquals(PermissionToken.FETCH.token, all[0].token)
    }

    // endregion
}
