package io.github.totomika.pocketmcp.app

import android.app.Application

/**
 * Application 入口。
 *
 * 初始化 AppContainer (手动 DI)。
 */
class MCPocketApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 预热 Room 数据库: 在后台线程触发 lazy, 避免 Compose 首帧时阻塞主线程
        Thread { container.database }.start()
    }
}

/**
 * 获取 AppContainer 的扩展函数。
 */
val Application.container: AppContainer
    get() = (this as MCPocketApplication).container
