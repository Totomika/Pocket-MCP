package io.github.totomika.pocketmcp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.START_STICKY
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.totomika.pocketmcp.MainActivity
import io.github.totomika.pocketmcp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MCPocket 前台服务。
 *
 * 保持 app 进程存活, 使 MCP Server (Ktor + QuickJS runtime) 能持续监听端口,
 * 不被系统在后台杀死。启动时显示常驻通知 "MCPocket 运行中"。
 *
 * - [START_STICKY]: 被杀后系统调度重启, [onStartCommand] 再次调用恢复服务。
 * - 启动时自动恢复所有 enabled=true 的服务 (crash 恢复)。
 * - foregroundServiceType = specialUse: 本地 MCP Server 监听端口无对应标准类型,
 *   归入 specialUse 并声明 subtype=local_mcp_server (Android 14+ 必须声明 FGS 类型)。
 */
class McpForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(activeCount = 0))

        // 恢复所有已启用的服务 (crash 重启 / app 重启后)
        scope.launch {
            try {
                val app = applicationContext as MCPocketApplication
                val count = app.container.serviceManager.restoreEnabledServices()
                app.container.logManager.system("Service restored $count service(s) on startup")
                updateNotification(count)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    private fun updateNotification(activeCount: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(activeCount))
    }

    /**
     * 构建前台服务常驻通知。
     */
    private fun buildNotification(activeCount: Int): Notification {
        val contentText = if (activeCount > 0) {
            getString(R.string.notification_services_active, activeCount)
        } else {
            getString(R.string.notification_waiting)
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_mcp)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pocketmcp_service"
        private const val NOTIFICATION_ID = 1

        /**
         * 创建通知渠道。需在启动服务前调用 (Android 8+ 强制要求)。
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                    setShowBadge(false)
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
        }

        /**
         * 启动前台服务。
         */
        fun start(context: Context) {
            createNotificationChannel(context)
            val intent = Intent(context, McpForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }
}
