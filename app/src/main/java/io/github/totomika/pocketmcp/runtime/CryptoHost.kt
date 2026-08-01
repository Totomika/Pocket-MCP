package io.github.totomika.pocketmcp.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * host.crypto 第 0 层注入器: 随机数 + Base64 编解码 + 摘要 (MD5/SHA)。
 *
 * 纯计算, **无 namespace 隔离 / 无权限 / 无 cleanup** —— 所有脚本均可使用。
 *
 * 注入时机: 由 [HostApiInjector.inject] 在第 0 层调用, **先于** [HostApiRegistry]
 * 的第 1-4 层注入。这样 FsApi 等在 evaluate JS wrapper 时 `host.crypto.*`
 * 已可用 (FsApi.readBytes 依赖 `host.crypto.b64decode`)。
 *
 * 实现选择:
 * - Base64 用 `java.util.Base64` (JDK 自带), 不再自写 JS 解码器 —— 顺带修复
 *   原 FsApi `_b64dec` 的两个缺陷 (c<64 guard 无效 / 畸形输入 RangeError)。
 * - 摘要用 `java.security.MessageDigest`, 输出 hex 小写。
 *
 * 见 docs/03-host-api.md 第 0 层。
 */
object CryptoHost {

    /**
     * 注入 host.crypto 全部能力。
     *
     * 单次注入即可 (第 0 层每 runtime 只注入一次), JS 侧 `host.crypto = { ... }`
     * 直接赋值, 不需要 `||` guard。
     */
    fun inject(quickJs: QuickJs) {
        val random = SecureRandom()

        // --- 随机数 ---
        quickJs.function<String>("__crypto_randomUUID") {
            UUID.randomUUID().toString()
        }
        quickJs.function<ByteArray>("__crypto_randomValues") { args ->
            val length = (args.firstOrNull() as? Number)?.toInt() ?: 0
            val bytes = ByteArray(length)
            random.nextBytes(bytes)
            bytes
        }

        // --- Base64 (RFC 4648) ---
        // encode: 接受 UTF-8 字符串 -> base64 字符串
        quickJs.function<String>("__crypto_b64encode") { args ->
            val input = args.firstOrNull()?.toString() ?: ""
            Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
        }
        // decode: base64 字符串 -> ByteArray (JS 侧包装为 Uint8Array)
        quickJs.function<ByteArray>("__crypto_b64decode") { args ->
            val input = args.firstOrNull()?.toString() ?: ""
            Base64.getDecoder().decode(input)
        }

        // --- 摘要 -> hex 小写 ---
        quickJs.function<String>("__crypto_md5") { args ->
            val input = args.firstOrNull()?.toString() ?: ""
            hashHex("MD5", input.toByteArray(Charsets.UTF_8))
        }
        quickJs.function<String>("__crypto_sha1") { args ->
            val input = args.firstOrNull()?.toString() ?: ""
            hashHex("SHA-1", input.toByteArray(Charsets.UTF_8))
        }
        quickJs.function<String>("__crypto_sha256") { args ->
            val input = args.firstOrNull()?.toString() ?: ""
            hashHex("SHA-256", input.toByteArray(Charsets.UTF_8))
        }

        // --- JS 包装层 ---
        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.crypto = {
                  // 随机数
                  randomUUID: () => __crypto_randomUUID(),
                  getRandomValues: (arr) => {
                    const bytes = __crypto_randomValues(arr.length);
                    for (let i = 0; i < arr.length; i++) arr[i] = bytes[i];
                    return arr;
                  },
                  // Base64 (RFC 4648)
                  b64encode: (str) => __crypto_b64encode(str),
                  b64decode: (str) => {
                    const bytes = __crypto_b64decode(str);
                    const out = new Uint8Array(bytes.length);
                    for (let i = 0; i < bytes.length; i++) out[i] = bytes[i];
                    return out;
                  },
                  // 摘要 -> hex 小写
                  md5:    (str) => __crypto_md5(str),
                  sha1:   (str) => __crypto_sha1(str),
                  sha256: (str) => __crypto_sha256(str),
                };
                """.trimIndent()
            )
        }
    }

    /** 计算摘要并转为 hex 小写。 */
    private fun hashHex(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}