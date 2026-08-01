package io.github.totomika.pocketmcp.documents

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * 透明无界面 Activity, 仅供外部文件管理器 (如 MT管理器) 在目标进程未运行时
 * 通过 startActivity 拉活本应用进程。
 *
 * - 启动后立即 finish, 用户无感知
 * - noHistory + excludeFromRecents 确保不污染最近任务列表
 * - 仅在 debug 构建可用 (与 MCPDataFilesProvider 同属 debug 源集)
 *
 * 典型调用方: MT管理器 "添加本地存储" 流程在首次访问 DocumentsProvider
 * 失败时, 会按约定拉起目标 app 内的 wake-up 入口, 让进程起来后再重试 SAF query。
 *
 * 此 Activity 不需要 authority 约定 —— 它只是个进程启动锚点, 外部通过
 * `adb shell am start -n io.github.totomika.pocketmcp/.documents.MCPDataWakeUpActivity`
 * 也可手动测试。
 */
class MCPDataWakeUpActivity : Activity() {

    companion object {
        private const val TAG = "MCPDataWakeUpActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "WakeUp invoked, finishing immediately")
        finish()
    }
}