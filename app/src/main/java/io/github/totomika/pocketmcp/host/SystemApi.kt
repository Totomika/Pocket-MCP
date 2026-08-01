package io.github.totomika.pocketmcp.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import io.github.totomika.pocketmcp.permission.PermissionToken
import io.github.totomika.pocketmcp.permission.SystemPermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * host.system API 实现。
 *
 * - clipboard.get/set (需权限 host.clipboard)
 * - deviceInfo() (需权限 host.deviceInfo)
 * - toast(msg) (需权限 host.toast, 同步不返回 Promise)
 * - openUrl(url) (需权限 host.openUrl)
 *
 * 见 docs/03-host-api.md 第 3 层。
 *
 * @param permissionChecker 系统能力权限检查 (M4 注入, null 时跳过检查)
 */
class SystemApi(
    private val context: Context,
    private val permissionChecker: SystemPermissionChecker? = null,
) : HostApi {

    override fun inject(quickJs: QuickJs, namespace: String, scope: CoroutineScope) {
        injectClipboard(quickJs, namespace)
        injectDeviceInfo(quickJs, namespace)
        injectToast(quickJs, namespace)
        injectOpenUrl(quickJs, namespace)
    }

    private fun injectClipboard(quickJs: QuickJs, namespace: String) {
        quickJs.asyncFunction<String?>("__system_clipboard_get") {
            // SECURITY: 此处检查权限
            permissionChecker?.check(namespace, PermissionToken.CLIPBOARD)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }

        quickJs.asyncFunction<Unit>("__system_clipboard_set") { args ->
            // SECURITY: 此处检查权限
            permissionChecker?.check(namespace, PermissionToken.CLIPBOARD)
            val text = args[0]?.toString() ?: ""
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MCPocket", text))
        }

        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.system = host.system || {};
                host.system.clipboard = {
                  get: () => __system_clipboard_get(),
                  set: (text) => __system_clipboard_set(text),
                };
            """.trimIndent()
            )
        }
    }

    private fun injectDeviceInfo(quickJs: QuickJs, namespace: String) {
        quickJs.asyncFunction<String>("__system_deviceInfo") {
            // SECURITY: 此处检查权限
            permissionChecker?.check(namespace, PermissionToken.DEVICE_INFO)
            val metrics = context.resources.displayMetrics
            """{"model":"${Build.MODEL}","androidVersion":"${Build.VERSION.RELEASE}","sdkVersion":${Build.VERSION.SDK_INT},"manufacturer":"${Build.MANUFACTURER}","screen":{"width":${metrics.widthPixels},"height":${metrics.heightPixels},"density":${metrics.density}}}"""
        }

        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.system = host.system || {};
                host.system.deviceInfo = () => __system_deviceInfo();
            """.trimIndent()
            )
        }
    }

    private fun injectToast(quickJs: QuickJs, namespace: String) {
        // toast 同步, 不返回 Promise
        quickJs.function<Any?>("__system_toast") { args ->
            // SECURITY: 此处检查权限
            permissionChecker?.check(namespace, PermissionToken.TOAST)
            val text = args[0]?.toString() ?: ""
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
            null
        }

        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.system = host.system || {};
                host.system.toast = (msg) => __system_toast(msg);
            """.trimIndent()
            )
        }
    }

    private fun injectOpenUrl(quickJs: QuickJs, namespace: String) {
        quickJs.asyncFunction<Unit>("__system_openUrl") { args ->
            // SECURITY: 此处检查权限
            permissionChecker?.check(namespace, PermissionToken.OPEN_URL)
            val url = args[0]?.toString() ?: ""
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.system = host.system || {};
                host.system.openUrl = (url) => __system_openUrl(url);
            """.trimIndent()
            )
        }
    }
}
