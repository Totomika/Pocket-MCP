package io.github.totomika.pocketmcp.script

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * URL 脚本导入器。
 *
 * 用 OkHttp 拉取脚本内容。
 * 见 docs/08-distribution.md "渠道 2: 粘贴/URL 导入"。
 *
 * 限制: 只允许 http/https, 30s 超时。
 */
class UrlImporter {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 从 URL 拉取脚本内容。
     *
     * @param url 脚本 URL (http/https)
     * @return 脚本源码
     * @throws IllegalArgumentException URL 格式无效
     * @throws Exception 网络错误
     */
    fun fetch(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("Only http/https schemes are allowed")
        }

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            return response.body.string()
        }
    }
}
