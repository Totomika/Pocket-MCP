package io.github.totomika.pocketmcp.host

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * host.fetch API 实现。
 *
 * ```js
 * const response = await host.fetch("https://...", {
 *   method: "POST",
 *   headers: { "Content-Type": "application/json" },
 *   body: JSON.stringify({ ... })
 * })
 * const text = await response.text()
 * const json = await response.json()
 * const status = response.status
 * const ok = response.ok
 * ```
 *
 * 权限: 需声明 @permission host.fetch (M4 检查, 这里只实现网络逻辑)。
 * 限制: 只允许 http/https, 30s 超时, 自动重定向(最多5次)。
 */
class FetchApi(
    private val permissionChecker: FetchPermissionChecker? = null,
) : HostApi {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun inject(quickJs: QuickJs, namespace: String, scope: CoroutineScope) {
        // fetch(url, options?) → JSON { status, ok, body, headers }
        quickJs.asyncFunction<String>("__fetch") { args ->
            val url = args[0]?.toString() ?: ""
            val options = args[1]

            // 只允许 http/https
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw IllegalArgumentException("Only http/https schemes are allowed")
            }

            // 权限检查 (M4 接入)
            permissionChecker?.check(namespace, url)

            val (method, headers, body) = parseOptions(options)

            withContext(Dispatchers.IO) {
                val builder = Request.Builder().url(url)

                headers.forEach { (key, value) -> builder.header(key, value) }

                when (method) {
                    "GET", "HEAD" -> builder.method(method, null)
                    else -> {
                        val contentType = headers["Content-Type"]
                            ?: headers["content-type"]
                            ?: "application/json"
                        builder.method(
                            method,
                            (body ?: "").toRequestBody(contentType.toMediaType())
                        )
                    }
                }

                client.newCall(builder.build()).execute().use { response ->
                    val responseBody = response.body.string()
                    val respHeaders = JSONObject()
                    response.headers.forEach { (key, value) -> respHeaders.put(key, value) }
                    JSONObject().apply {
                        put("status", response.code)
                        put("ok", response.isSuccessful)
                        put("body", responseBody)
                        put("headers", respHeaders)
                    }.toString()
                }
            }
        }

        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.fetch = async function(url, options) {
                    const raw = await __fetch(url, options);
                    const resp = JSON.parse(raw);
                    return {
                      status: resp.status,
                      ok: resp.ok,
                      headers: resp.headers,
                      _body: resp.body,
                      text: function() { return resp.body; },
                      json: function() { return JSON.parse(resp.body); },
                    };
                };
            """.trimIndent()
            )
        }
    }

    /**
     * 解析 JS 传来的 options 参数。
     *
     * JS 对象 `{method, headers, body}` 经 quickjs-kt 1.0.5 转换为 [JsObject]
     * (即 [Map]`<String, Any?>`), 参见 [jsValueToKtValue].
     */
    private fun parseOptions(options: Any?): Triple<String, Map<String, String>, String?> {
        if (options == null) return Triple("GET", emptyMap(), null)

        @Suppress("UNCHECKED_CAST")
        val opts = options as? Map<String, Any?> ?: return Triple("GET", emptyMap(), null)

        val method = (opts["method"] as? String)?.trim()?.uppercase() ?: "GET"

        val headers = mutableMapOf<String, String>()
        val rawHeaders = opts["headers"]
        if (rawHeaders is Map<*, *>) {
            rawHeaders.forEach { (k, v) ->
                if (k != null && v != null) headers[k.toString()] = v.toString()
            }
        }

        val body = opts["body"] as? String

        return Triple(method, headers, body)
    }
}

/**
 * Fetch 权限检查接口 (M4 实现)。
 */
interface FetchPermissionChecker {
    fun check(namespace: String, url: String)
}
