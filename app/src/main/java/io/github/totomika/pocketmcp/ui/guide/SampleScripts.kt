package io.github.totomika.pocketmcp.ui.guide

/**
 * 内置示例脚本。
 *
 * 注意: JS 模板字符串中的 ${'$'} 是 Kotlin 字符串转义, 生成 JS 的 $ 符号。
 */
object SampleScripts {

    val hello = """
        // @name Hello
        // @namespace hello
        // @version 1.0.0
        // @description 简单问候工具, 用于测试连接

        mcp.tool("greet", "Greet the user", {
          type: "object",
          properties: { name: { type: "string", description: "Name to greet" } }
        }, async (args) => {
          return { content: [{ type: "text", text: "Hello, " + (args.name || "World") + "!" }] };
        });
    """.trimIndent()

    val memory = """
        // @name Memory
        // @namespace memory
        // @version 1.0.0
        // @description 持久化键值记忆系统
        // @instructions 使用 memory.read 读取记忆, memory.write 写入记忆。

        mcp.tool("read", "Read a memory by key", {
          type: "object",
          properties: { key: { type: "string" } },
          required: ["key"]
        }, async (args) => {
          const value = await host.kv.get(args.key);
          return { content: [{ type: "text", text: value ?? "(empty)" }] };
        });

        mcp.tool("write", "Write a memory", {
          type: "object",
          properties: { key: { type: "string" }, value: { type: "string" } },
          required: ["key", "value"]
        }, async (args) => {
          await host.kv.set(args.key, args.value);
          return { content: [{ type: "text", text: "saved" }] };
        });

        mcp.tool("list", "List all memory keys", {}, async () => {
          const keys = await host.kv.list();
          return { content: [{ type: "text", text: keys.join("\n") || "(empty)" }] };
        });

        mcp.tool("delete", "Delete a memory", {
          type: "object",
          properties: { key: { type: "string" } },
          required: ["key"]
        }, async (args) => {
          await host.kv.delete(args.key);
          return { content: [{ type: "text", text: "deleted" }] };
        });
    """.trimIndent()
}
