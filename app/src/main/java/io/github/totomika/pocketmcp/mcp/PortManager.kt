package io.github.totomika.pocketmcp.mcp

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 端口管理器。
 *
 * 见 docs/07-android.md "端口管理"。
 *
 * - 默认端口池: 8080-8089 (可扩展)
 * - 每个 Service 固定端口, 存储在 ServiceEntry
 * - 端口被占 → 抛 PortInUseException
 * - AI 客户端配置一次永久有效 (端口不变)
 */
class PortManager {

    /**
     * 默认端口池范围。
     */
    val portRange: IntRange = DEFAULT_PORT_RANGE

    /**
     * 检查端口是否可用 (未被占用)。
     *
     * SECURITY: 只检查端口可用性, 不绑定长持有。
     * 使用 SO_REUSEADDR 避免 TIME_WAIT 状态影响。
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 在默认端口池中找一个可用端口 (不实际绑定, 仅预览)。
     *
     * 从 [portRange] 取第一个未在 `usedPorts` 中且 [isPortAvailable] 的端口。
     * 跳过已分配的端口 (由 ServiceManager 传入)。
     *
     * 注意: 与实际 [validatePort] 之间存在 TOCTOU 窗口 (端口状态可能变化),
     * 实际持久化时仍会再做一次 [validatePort] 校验。
     *
     * @param usedPorts 已分配的端口集合 (已创建 Service 的端口)
     * @return 第一个可用端口号; 端口池全部占用时返回 null
     */
    fun findFreePort(usedPorts: Set<Int> = emptySet()): Int? {
        for (port in portRange) {
            if (port in usedPorts) continue
            if (isPortAvailable(port)) return port
        }
        return null
    }

    /**
     * 验证指定端口是否可用且未被分配。
     *
     * @throws PortInUseException 端口被占用
     */
    fun validatePort(port: Int, usedPorts: Set<Int> = emptySet()) {
        if (port in usedPorts) {
            throw PortInUseException("Port $port already assigned to another service")
        }
        if (!isPortAvailable(port)) {
            throw PortInUseException("Port $port is in use")
        }
    }

    companion object {
        /** 默认端口池: */
        val DEFAULT_PORT_RANGE = 9000..9099
    }
}

/**
 * 端口被占用异常。
 */
class PortInUseException(message: String) : Exception(message)

/**
 * 服务名已被占用异常 (重构后由 ServiceManager 显式校验, 替代原 Room unique index)。
 */
class ServiceNameInUseException(val name: String) :
    Exception("Service name '$name' is already in use")
