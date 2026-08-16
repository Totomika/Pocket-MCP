package io.github.totomika.pocketmcp.runtime

/**
 * 协程 ↔ Promise 桥接。
 *
 * Kotlin → JS: evaluate async IIFE 包裹 handler, await Promise, 返回结果 JSON。
 * JS → Kotlin: 通过 asyncFunction binding, 返回 Promise。
 */
class QuickJsBridge {

    /**
     * 调用 JS handler 并返回结果 JSON 字符串。
     *
     * 经 [RuntimeEntry.runJs] 在 runtime 专属线程上执行 (JS 只跑在 dispatcher 线程)。
     *
     * 注意: quickjs-kt 的 evaluate 返回 evaluation 的直接结果。
     * async IIFE 返回 Promise 对象, 不是 resolved value。
     * 解决: 第一次 evaluate 启动 async IIFE, 结果存全局变量;
     * evaluate 会自动 drain pending jobs (awaitAsyncJobs);
     * 第二次 evaluate 读取全局变量。
     *
     * **防 null**: 若 QuickJS async 基础设施损坏 (如 OOM 后死 promise),
     * handler 的 await 永不返回, __bridge_result 保持 null。
     * 此时标记 runtime poisoned 并抛异常, 阻止后续调用继续派往已损坏的引擎。
     *
     * @param runtime 目标 runtime
     * @param toolName 工具本地名 (不含 namespace 前缀)
     * @param argsJson 参数 JSON 字符串
     * @return handler 返回的 CallToolResult JSON 字符串
     * @throws IllegalStateException 引擎损坏时 (result 为 null), runtime 被标记 poisoned
     */
    suspend fun callHandler(
        runtime: RuntimeEntry,
        toolName: String,
        argsJson: String,
    ): String = runtime.runJs {
        // 第一次 evaluate: 启动 async IIFE, 结果存全局 __bridge_result
        // evaluate 会 awaitAsyncJobs, 等 Promise resolve 后返回
        val launchCode = """
            globalThis.__bridge_result = null;
            globalThis.__bridge_error = null;
            (async () => {
                try {
                    const tool = mcp._tools.find(t => t.name === ${"\"$toolName\""});
                    if (!tool) throw new Error("Tool not found: $toolName");
                    const args = $argsJson;
                    const result = await tool.handler(args);
                    globalThis.__bridge_result = JSON.stringify(result);
                } catch (e) {
                    globalThis.__bridge_error = e.message || String(e);
                }
            })();
        """.trimIndent()
        try {
            evaluate<Any?>(launchCode)
        } catch (e: Exception) {
            // 第一个 evaluate 抛异常 = QuickJS 引擎级损坏 (OOM, "Result promise not found" 等)
            // JS 层错误已被 IIFE 的 try-catch 兜住, 不会到这。到这里的都是引擎级故障。
            runtime.poison(PoisonReason.BRIDGE_CORRUPTED)
            throw e
        }

        // 第二次 evaluate: 读取结果
        // 用 Any? 而非 String, 防止引擎损坏时 evaluate 返回 null 触发 Kotlin NPE
        val resultCode = """
            if (globalThis.__bridge_error) {
                throw new Error(globalThis.__bridge_error);
            }
            globalThis.__bridge_result
        """.trimIndent()
        val result = evaluate<Any?>(resultCode)
        if (result == null) {
            // __bridge_result 为 null 说明 async IIFE 没正常完成
            // (handler 卡在死 promise 上 — QuickJS async 基础设施已损坏)
            runtime.poison(PoisonReason.BRIDGE_CORRUPTED)
            throw IllegalStateException(
                "Bridge result is null — QuickJS async infrastructure may be corrupted " +
                        "(tool=$toolName). Runtime marked as poisoned."
            )
        }
        result as String
    }
}
