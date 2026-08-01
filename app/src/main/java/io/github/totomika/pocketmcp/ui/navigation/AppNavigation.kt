package io.github.totomika.pocketmcp.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.ui.data.DataOverviewScreen
import io.github.totomika.pocketmcp.ui.data.kv.KvManageScreen
import io.github.totomika.pocketmcp.ui.data.sql.SqlConsoleScreen
import io.github.totomika.pocketmcp.ui.data.sql.SqlDbDetailScreen
import io.github.totomika.pocketmcp.ui.data.sql.SqlDbListScreen
import io.github.totomika.pocketmcp.ui.data.sql.SqlTableScreen
import io.github.totomika.pocketmcp.ui.guide.FirstRunGuideScreen
import io.github.totomika.pocketmcp.ui.logs.LogsScreen
import io.github.totomika.pocketmcp.ui.scripts.AddScriptScreen
import io.github.totomika.pocketmcp.ui.scripts.ScriptDetailScreen
import io.github.totomika.pocketmcp.ui.scripts.ScriptsScreen
import io.github.totomika.pocketmcp.ui.services.ServiceDetailScreen
import io.github.totomika.pocketmcp.ui.services.ServicesScreen

data class BottomNavItem(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Routes.SCRIPTS, R.string.nav_scripts, Icons.Filled.Code),
    BottomNavItem(Routes.SERVICES, R.string.nav_services, Icons.Filled.Dns),
    BottomNavItem(Routes.LOGS, R.string.logs_title, Icons.AutoMirrored.Filled.Article),
)

/**
 * 底部 Tab 路由索引表, 用于判断 Tab 间切换的动画方向。
 * 索引即 Tab 在 [bottomNavItems] 中的视觉位置 (脚本=0, 服务=1, 日志=2)。
 */
private val tabRouteIndex: Map<String, Int> =
    bottomNavItems.withIndex().associate { (i, item) -> item.route to i }

/**
 * 返回路由在底部 Tab 中的索引; 非 Tab 路由返回 null。
 */
private fun tabIndexOf(route: String?): Int? =
    route?.let { tabRouteIndex[it] }

/**
 * 是否为"向左切换 Tab" (目标 Tab 索引 < 当前 Tab 索引)。
 * 这种情况下视觉上应是"左→右"的返回动画, 而非默认的"右→左"前进动画。
 */
private fun isTabSwitchBackward(
    fromRoute: String?,
    toRoute: String?,
): Boolean {
    val from = tabIndexOf(fromRoute)
    val to = tabIndexOf(toRoute)
    return from != null && to != null && to < from
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SCRIPTS,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                // 向左切 Tab 时新页从左侧滑入 (视觉上的"返回"); 否则默认从右侧滑入 (前进)。
                if (isTabSwitchBackward(initialState.destination.route, targetState.destination.route)) {
                    slideInHorizontally(tween(220)) { fullWidth -> -fullWidth } +
                        fadeIn(tween(220))
                } else {
                    slideInHorizontally(tween(220)) { fullWidth -> fullWidth } +
                        fadeIn(tween(220))
                }
            },
            exitTransition = {
                // 向左切 Tab 时旧页向右侧滑出; 否则默认向左侧滑出 (前进)。
                if (isTabSwitchBackward(initialState.destination.route, targetState.destination.route)) {
                    slideOutHorizontally(tween(220)) { fullWidth -> fullWidth } +
                        fadeOut(tween(220))
                } else {
                    slideOutHorizontally(tween(220)) { fullWidth -> -fullWidth } +
                        fadeOut(tween(220))
                }
            },
            popEnterTransition = {
                slideInHorizontally(tween(220)) { fullWidth -> -fullWidth } +
                    fadeIn(tween(220))
            },
            popExitTransition = {
                slideOutHorizontally(tween(220)) { fullWidth -> fullWidth } +
                    fadeOut(tween(220))
            },
        ) {
            composable(Routes.SCRIPTS) {
                ScriptsScreen(
                    onScriptClick = { namespace ->
                        navController.navigate(Routes.scriptDetail(namespace))
                    },
                    onAddClick = {
                        navController.navigate(Routes.ADD_SCRIPT)
                    },
                )
            }

            composable(Routes.SERVICES) {
                ServicesScreen(
                    onServiceClick = { id ->
                        navController.navigate(Routes.serviceDetail(id))
                    },
                )
            }

            composable(Routes.LOGS) {
                LogsScreen()
            }

            composable(Routes.ADD_SCRIPT) {
                AddScriptScreen(
                    onBack = { navController.popBackStack() },
                    onImported = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.SCRIPT_DETAIL,
                arguments = listOf(navArgument("namespace") { type = NavType.StringType }),
            ) { entry ->
                val namespace = entry.arguments?.getString("namespace") ?: return@composable
                ScriptDetailScreen(
                    namespace = namespace,
                    onBack = { navController.popBackStack() },
                    onNavigateToService = { id ->
                        navController.navigate(Routes.serviceDetail(id))
                    },
                    onNavigateToDataManagement = { ns ->
                        navController.navigate(Routes.dataOverview(ns))
                    },
                )
            }

            composable(
                route = Routes.SERVICE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ServiceDetailScreen(
                    serviceId = id,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.GUIDE) {
                FirstRunGuideScreen(
                    onComplete = {
                        navController.navigate(Routes.SCRIPTS) {
                            popUpTo(0)
                        }
                    },
                )
            }

            // ── 数据管理 (脚本运行时数据浏览器) ──

            composable(
                route = Routes.DATA_OVERVIEW,
                arguments = listOf(navArgument("namespace") { type = NavType.StringType }),
            ) { entry ->
                val namespace = entry.arguments?.getString("namespace") ?: return@composable
                DataOverviewScreen(
                    namespace = namespace,
                    onBack = { navController.popBackStack() },
                    onNavigateToKv = { ns -> navController.navigate(Routes.dataKv(ns)) },
                    onNavigateToSql = { ns -> navController.navigate(Routes.dataSql(ns)) },
                )
            }

            composable(
                route = Routes.DATA_KV,
                arguments = listOf(navArgument("namespace") { type = NavType.StringType }),
            ) { entry ->
                val namespace = entry.arguments?.getString("namespace") ?: return@composable
                KvManageScreen(
                    namespace = namespace,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.DATA_SQL,
                arguments = listOf(navArgument("namespace") { type = NavType.StringType }),
            ) { entry ->
                val namespace = entry.arguments?.getString("namespace") ?: return@composable
                SqlDbListScreen(
                    namespace = namespace,
                    onBack = { navController.popBackStack() },
                    onNavigateToDb = { ns, dbName -> navController.navigate(Routes.dataSqlDb(ns, dbName)) },
                )
            }

            composable(
                route = Routes.DATA_SQL_DB,
                arguments = listOf(
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("dbName") { type = NavType.StringType },
                ),
            ) { entry ->
                val namespace = entry.arguments?.getString("namespace") ?: return@composable
                val dbName = entry.arguments?.getString("dbName") ?: return@composable
                SqlDbDetailScreen(
                    namespace = namespace,
                    dbName = dbName,
                    onBack = { navController.popBackStack() },
                    onNavigateToTable = { ns, db, table -> navController.navigate(Routes.dataSqlTable(ns, db, table)) },
                    onNavigateToConsole = { ns, db -> navController.navigate(Routes.dataSqlConsole(ns, db)) },
                )
            }

            composable(
                route = Routes.DATA_SQL_TABLE,
                arguments = listOf(
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("dbName") { type = NavType.StringType },
                    navArgument("tableName") { type = NavType.StringType },
                ),
            ) { entry ->
                val namespace = entry.arguments?.getString("namespace") ?: return@composable
                val dbName = entry.arguments?.getString("dbName") ?: return@composable
                val tableName = entry.arguments?.getString("tableName") ?: return@composable
                SqlTableScreen(
                    namespace = namespace,
                    dbName = dbName,
                    tableName = tableName,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.DATA_SQL_CONSOLE,
                arguments = listOf(
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("dbName") { type = NavType.StringType },
                ),
            ) { entry ->
                val namespace = entry.arguments?.getString("namespace") ?: return@composable
                val dbName = entry.arguments?.getString("dbName") ?: return@composable
                SqlConsoleScreen(
                    namespace = namespace,
                    dbName = dbName,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
