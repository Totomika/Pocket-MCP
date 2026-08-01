package io.github.totomika.pocketmcp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.totomika.pocketmcp.app.McpForegroundService
import io.github.totomika.pocketmcp.ui.guide.FirstRunGuideScreen
import io.github.totomika.pocketmcp.ui.guide.GuideViewModel
import io.github.totomika.pocketmcp.ui.navigation.AppNavigation
import io.github.totomika.pocketmcp.ui.theme.MCPocketTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startMcpService()

        setContent {
            MCPocketTheme {
                val guideViewModel: GuideViewModel = viewModel()
                val isFirstRun = !guideViewModel.isCompleted

                if (isFirstRun) {
                    FirstRunGuideScreen(
                        onComplete = { recreate() },
                    )
                } else {
                    AppNavigation()
                }
            }
        }
    }

    private fun startMcpService() {
        McpForegroundService.start(this)
    }
}