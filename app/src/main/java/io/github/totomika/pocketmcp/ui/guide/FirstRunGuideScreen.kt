package io.github.totomika.pocketmcp.ui.guide

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.mcp.ServiceNameInUseException
import io.github.totomika.pocketmcp.script.ScriptManager
import io.github.totomika.pocketmcp.script.ScriptSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 安装状态, 驱动 UI 反馈 (loading / error / idle)。
 */
sealed class InstallState {
    /** 空闲, 可点击安装 */
    object Idle : InstallState()

    /** 正在安装中, 按钮 disabled + 显示 loading */
    object Installing : InstallState()

    /** 安装出错, 显示错误信息, 允许重试 */
    data class Error(val message: String) : InstallState()
}

class GuideViewModel(private val app: Application) : AndroidViewModel(app) {
    private val scriptManager by lazy { app.container.scriptManager }
    private val serviceManager by lazy { app.container.serviceManager }
    private val scriptRepository by lazy { app.container.scriptRepository }
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isCompleted: Boolean get() = prefs.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }

    /**
     * 安装示例脚本并自动创建默认服务。
     *
     * 重构后适配 manifest-based 逻辑:
     * 1. 逐个调用 [ScriptManager.importScript], 检查 [ScriptManager.ImportResult]
     * 2. 收集成功导入的 namespace 列表
     * 3. 若有脚本, 自动创建 "默认服务" 并将脚本映射到该服务
     * 4. 成功后回调 [onDone] (由调用方 markCompleted + navigate)
     */
    fun installSampleScripts(installHello: Boolean, installMemory: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            _installState.value = InstallState.Installing
            try {
                val namespaces = mutableListOf<String>()

                if (installHello) {
                    val result =
                        scriptManager.importScript(SampleScripts.hello, ScriptSourceType.PASTE)
                    if (result is ScriptManager.ImportResult.Error) {
                        _installState.value =
                            InstallState.Error(app.getString(R.string.guide_hello_import_failed, result.message ?: ""))
                        return@launch
                    }
                    // Imported / SameVersion / UpdateAvailable / OlderVersion — 脚本均存在于磁盘
                    namespaces.add("hello")
                }

                if (installMemory) {
                    val result =
                        scriptManager.importScript(SampleScripts.memory, ScriptSourceType.PASTE)
                    if (result is ScriptManager.ImportResult.Error) {
                        _installState.value =
                            InstallState.Error(app.getString(R.string.guide_memory_import_failed, result.message ?: ""))
                        return@launch
                    }
                    namespaces.add("memory")
                }

                // 有脚本时自动创建默认服务并映射
                if (namespaces.isNotEmpty()) {
                    createDefaultService(namespaces)
                }

                _installState.value = InstallState.Idle
                onDone()
            } catch (e: Exception) {
                _installState.value = InstallState.Error(e.message ?: app.getString(R.string.install_failed))
            }
        }
    }

    /**
     * 创建默认服务并将脚本列表映射到该服务。
     *
     * 若 "默认服务" 已存在 (例如上次引导残留), 则将脚本添加到现有服务而非报错。
     */
    private suspend fun createDefaultService(namespaces: List<String>) {
        val port = serviceManager.findNextPort()
            ?: throw IllegalStateException(app.getString(R.string.no_available_port))
        try {
            val service = serviceManager.createService("默认服务", port)
            for (ns in namespaces) {
                val code = scriptRepository.readScriptCode(ns) ?: continue
                serviceManager.addScriptToService(service.id, ns, code, enabled = true)
            }
        } catch (e: ServiceNameInUseException) {
            // "默认服务" 已存在 — 将脚本添加到现有服务
            val existing = serviceManager.getAllServices().firstOrNull { it.name == "默认服务" }
            if (existing != null) {
                for (ns in namespaces) {
                    val code = scriptRepository.readScriptCode(ns) ?: continue
                    serviceManager.addScriptToService(existing.id, ns, code, enabled = true)
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "pocketmcp_prefs"
        private const val KEY_COMPLETED = "first_run_completed"
    }
}

@Composable
fun FirstRunGuideScreen(
    onComplete: () -> Unit,
    viewModel: GuideViewModel = viewModel(),
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()

    var installHello by remember { mutableStateOf(true) }
    var installMemory by remember { mutableStateOf(true) }
    val installState by viewModel.installState.collectAsStateWithLifecycle()

    // 系统返回键: 非首页时回到上一页
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> WelcomePage(
                    onNext = { scope.launch { pagerState.animateScrollToPage(1) } },
                )

                1 -> PermissionsPage(
                    onBack = { scope.launch { pagerState.animateScrollToPage(0) } },
                    onNext = { scope.launch { pagerState.animateScrollToPage(2) } },
                )

                2 -> SampleScriptsPage(
                    installHello = installHello,
                    installMemory = installMemory,
                    onHelloChange = { installHello = it },
                    onMemoryChange = { installMemory = it },
                    installState = installState,
                    onRetry = { viewModel.resetInstallState() },
                    onInstall = {
                        viewModel.installSampleScripts(installHello, installMemory) {
                            viewModel.markCompleted()
                            onComplete()
                        }
                    },
                    onSkip = {
                        viewModel.markCompleted()
                        onComplete()
                    },
                )
            }
        }

        // ── Indicator dots ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                )
            }
        }
    }
}

// ── Page 1: Welcome ──

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Terminal,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.guide_welcome_tagline),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.guide_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.guide_get_started), style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ── Page 2: 可选权限 ──

@Composable
private fun PermissionsPage(
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current

    // 权限状态: 从系统设置返回时 (ON_RESUME) 刷新
    var notifGranted by remember { mutableStateOf(checkNotificationGranted(context)) }
    var fileAccessGranted by remember { mutableStateOf(checkFileAccessGranted()) }
    var batteryGranted by remember { mutableStateOf(checkBatteryOptimizationIgnored(context)) }
    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notifGranted = checkNotificationGranted(context)
        }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = checkNotificationGranted(context)
                fileAccessGranted = checkFileAccessGranted()
                batteryGranted = checkBatteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GuidePageHeader(title = stringResource(R.string.guide_permissions_title), onBack = onBack)
        Text(
            stringResource(R.string.guide_permissions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionCard(
            icon = Icons.Filled.Notifications,
            title = stringResource(R.string.permission_notification_title),
            subtitle = stringResource(R.string.permission_notification_subtitle),
            granted = notifGranted,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )

        PermissionCard(
            icon = Icons.Filled.Folder,
            title = stringResource(R.string.permission_file_access_title),
            subtitle = stringResource(R.string.permission_file_access_subtitle),
            granted = fileAccessGranted,
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }
            },
        )

        PermissionCard(
            icon = Icons.Filled.BatteryFull,
            title = stringResource(R.string.permission_battery_title),
            subtitle = stringResource(R.string.permission_battery_subtitle),
            granted = batteryGranted,
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            },
        )

        Spacer(Modifier.weight(1f))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.next))
        }
    }
}

// ── Page 3: 示例脚本 ──

@Composable
private fun SampleScriptsPage(
    installHello: Boolean,
    installMemory: Boolean,
    onHelloChange: (Boolean) -> Unit,
    onMemoryChange: (Boolean) -> Unit,
    installState: InstallState,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
) {
    val isInstalling = installState is InstallState.Installing
    val errorMessage = (installState as? InstallState.Error)?.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GuidePageHeader(title = stringResource(R.string.guide_sample_scripts_title), onBack = onSkip)
        Text(
            stringResource(R.string.guide_sample_scripts_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SampleScriptCard(
            title = stringResource(R.string.guide_script_hello_title),
            description = stringResource(R.string.guide_script_hello_desc),
            checked = installHello,
            enabled = !isInstalling,
            onCheckedChange = onHelloChange,
        )

        SampleScriptCard(
            title = stringResource(R.string.guide_script_memory_title),
            description = stringResource(R.string.guide_script_memory_desc),
            checked = installMemory,
            enabled = !isInstalling,
            onCheckedChange = onMemoryChange,
        )

        // 错误信息
        if (errorMessage != null) {
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // 安装按钮: loading / 重试 / 正常
        Button(
            onClick = {
                if (installState is InstallState.Error) onRetry()
                onInstall()
            },
            enabled = !isInstalling && (installHello || installMemory),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isInstalling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.installing))
            } else if (installState is InstallState.Error) {
                Text(stringResource(R.string.retry))
            } else {
                Text(stringResource(R.string.install_and_start))
            }
        }
        OutlinedButton(
            onClick = onSkip,
            enabled = !isInstalling,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.skip))
        }
    }
}

// ── Helper composables ──

/**
 * 页面顶部: 返回按钮 + 标题。
 */
@Composable
private fun GuidePageHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.nav_back),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (granted) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.granted),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                OutlinedButton(onClick = onAction) { Text(stringResource(R.string.go_to_settings)) }
            }
        }
    }
}

@Composable
private fun SampleScriptCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (checked) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ── 权限状态检查 ──

/** 通知权限 (POST_NOTIFICATIONS) 是否已授权。 */
private fun checkNotificationGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

/** 文件访问权限 (MANAGE_EXTERNAL_STORAGE) 是否已授权。 */
private fun checkFileAccessGranted(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

/** 电池优化白名单是否已加入。 */
private fun checkBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
