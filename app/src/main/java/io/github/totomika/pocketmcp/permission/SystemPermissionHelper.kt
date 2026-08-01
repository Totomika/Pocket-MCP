package io.github.totomika.pocketmcp.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Android 系统权限检查 (第 1 层权限, "总闸")。
 *
 * 见 docs/04-permissions.md "两层权限模型"。
 *
 * - MANAGE_EXTERNAL_STORAGE → 共享文件访问 (host.fs.shared)
 * - INTERNET → 网络 (host.fetch, Android 自动授予)
 * - FOREGROUND_SERVICE → 前台服务 (已在 Manifest 声明)
 * - POST_NOTIFICATIONS → 通知 (Android 13+)
 *
 * 运行时: 总闸 + 分闸 (脚本权限) 都开才放行。
 */
object SystemPermissionHelper {

    /**
     * 检查 MANAGE_EXTERNAL_STORAGE 是否已授权 (host.fs.shared 需要)。
     *
     * Android 11+ (API 30+): 需要 MANAGE_EXTERNAL_STORAGE,
     * 用户需在系统设置中手动授权。
     * Android 10 及以下: 使用旧版存储模型。
     */
    fun hasManageExternalStorage(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下: 旧版存储模型
            // minSdk 26, 简化处理
            true
        }
    }

    /**
     * 检查 INTERNET 权限 (host.fetch 需要)。
     * INTERNET 是 normal permission, 安装时自动授予, 无需运行时请求。
     */
    fun hasInternet(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(android.Manifest.permission.INTERNET) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查 host.fs.shared 所需的系统权限。
     * @return true=已授权, false=需引导用户去设置
     */
    fun checkFsSharedSystemPermission(context: Context): Boolean {
        return hasManageExternalStorage(context)
    }

    /**
     * 检查 host.fetch 所需的系统权限。
     * INTERNET 是自动授予的, 通常总是 true。
     */
    fun checkFetchSystemPermission(context: Context): Boolean {
        return hasInternet(context)
    }

    /**
     * 创建引导用户去系统设置授权 MANAGE_EXTERNAL_STORAGE 的 Intent。
     */
    fun createManageStorageSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
