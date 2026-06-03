package com.winter.durianai.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winter.durianai.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.winter.durianai.ui.AppViewModel
import com.winter.durianai.ui.ThemeMode
import com.winter.durianai.ui.components.DurianEmoji
import com.winter.durianai.ui.screens.agent.AgentChatScreen
import com.winter.durianai.ui.screens.agent.AgentChatNavigationEvent
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.ui.screens.camera.CameraCaptureScreen
import com.winter.durianai.ui.screens.nativeui.AboutDoranScreen
import com.winter.durianai.ui.screens.nativeui.DashboardScreen
import com.winter.durianai.ui.screens.nativeui.ModelManagerScreen
import com.winter.durianai.ui.screens.nativeui.ReportDetailScreen
import com.winter.durianai.ui.screens.nativeui.SettingsScreen
import com.winter.durianai.ui.screens.nativeui.StatsReportScreen
import com.winter.durianai.ui.screens.nativeui.ProfileScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    appViewModel: AppViewModel,
    startRoute: String? = null,
    onStartRouteConsumed: () -> Unit = {},
    onOpenFloatingBall: () -> Unit = {}
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val agentChatViewModel: AgentChatViewModel = viewModel()
    var drawerNavigationInProgress by remember { mutableStateOf(false) }

    fun navigateFromDrawer(route: String) {
        if (drawerNavigationInProgress) return
        scope.launch {
            drawerNavigationInProgress = true
            try {
                if (drawerState.isOpen) {
                    drawerState.close()
                }
                if (navController.currentDestination?.route != route) {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            } finally {
                drawerNavigationInProgress = false
            }
        }
    }

    LaunchedEffect(startRoute) {
        if (!startRoute.isNullOrBlank()) {
            navController.navigate(startRoute) {
                launchSingleTop = true
            }
            onStartRouteConsumed()
        }
    }

    LaunchedEffect(navController, agentChatViewModel) {
        agentChatViewModel.navigationEvents.collect { event ->
            when (event) {
                AgentChatNavigationEvent.OpenHistory -> navController.navigate("history") { launchSingleTop = true }
                AgentChatNavigationEvent.OpenModelManager -> navController.navigate("model_manager") { launchSingleTop = true }
                AgentChatNavigationEvent.OpenSettings -> navController.navigate("settings") { launchSingleTop = true }
                AgentChatNavigationEvent.OpenProfile -> navController.navigate("profile") { launchSingleTop = true }
                AgentChatNavigationEvent.OpenStats -> navController.navigate("stats") { launchSingleTop = true }
                AgentChatNavigationEvent.OpenWidgets -> navController.navigate("widgets") { launchSingleTop = true }
                AgentChatNavigationEvent.OpenAbout -> navController.navigate("about") { launchSingleTop = true }
                is AgentChatNavigationEvent.OpenReport -> navController.navigate("report_detail/${event.sessionId}") { launchSingleTop = true }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                navController = navController,
                appViewModel = appViewModel,
                onOpenSettings = { navigateFromDrawer("settings") },
                onOpenModelManager = { navigateFromDrawer("model_manager") },
                onNavigateFromDrawer = ::navigateFromDrawer
            )
        }
    ) {
        NavHost(navController = navController, startDestination = "dashboard") {
            composable("dashboard") {
                DashboardScreen(
                    appViewModel = appViewModel,
                    agentChatViewModel = agentChatViewModel,
                    onOpenDrawer = {
                        scope.launch {
                            if (!drawerState.isOpen) drawerState.open()
                        }
                    },
                    onNavigateToAgent = { navController.navigate("agent_chat") },
                    onNavigateToAchievements = { navController.navigate("profile") },
                    onOpenLatestReport = { sessionId -> navController.navigate("report_detail/$sessionId") },
                    onOpenFloatingBall = onOpenFloatingBall
                )
            }
            composable("agent_chat") {
                AgentChatScreen(
                    viewModel = agentChatViewModel,
                    onNavigateToProfile = { navController.navigate("profile") },
                    onNavigateToHistory = { navController.navigate("history") },
                    onNavigateToModelManager = { navController.navigate("model_manager") },
                    onNavigateToCamera = { navController.navigate("camera_capture") },
                    appViewModel = appViewModel,
                    onOpenDrawer = {
                        scope.launch {
                            if (!drawerState.isOpen) drawerState.open()
                        }
                    }
                )
            }
            composable("camera_capture") {
                CameraCaptureScreen(
                    agentChatViewModel = agentChatViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("profile") {
                ProfileScreen(
                    agentChatViewModel = agentChatViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("history") {
                com.winter.durianai.ui.screens.nativeui.HistoryScreen(
                    agentChatViewModel = agentChatViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSession = { sessionId ->
                        agentChatViewModel.switchSession(sessionId)
                        navController.navigate("agent_chat") { launchSingleTop = true }
                    },
                    onOpenReport = { sessionId -> navController.navigate("report_detail/$sessionId") }
                )
            }
            composable("report_detail/{sessionId}") { backStackEntry ->
                ReportDetailScreen(
                    agentChatViewModel = agentChatViewModel,
                    sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    appViewModel = appViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("model_manager") {
                ModelManagerScreen(
                    agentChatViewModel = agentChatViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("stats") {
                StatsReportScreen(
                    agentChatViewModel = agentChatViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("about") {
                AboutDoranScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("widgets") {
                com.winter.durianai.ui.screens.nativeui.WidgetsScreen(
                    agentChatViewModel = agentChatViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun AppDrawer(
    navController: NavHostController,
    appViewModel: AppViewModel,
    onOpenSettings: () -> Unit,
    onOpenModelManager: () -> Unit,
    onNavigateFromDrawer: (String) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val themeMode by appViewModel.themeMode.collectAsState()

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(300.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                DurianEmoji(size = 28.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Doran AI Edge",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(16.dp))

        DrawerItem(stringResource(id = R.string.drawer_home), Icons.Default.Home, currentRoute == "dashboard") {
            onNavigateFromDrawer("dashboard")
        }
        
        DrawerItem(stringResource(id = R.string.drawer_pick_durian), Icons.Default.AutoAwesome, currentRoute == "agent_chat") {
            onNavigateFromDrawer("agent_chat")
        }
        
        DrawerItem(stringResource(id = R.string.drawer_history), Icons.Default.History, currentRoute == "history") {
            onNavigateFromDrawer("history")
        }

        DrawerItem(stringResource(id = R.string.drawer_stats), Icons.Default.Timeline, currentRoute == "stats") {
            onNavigateFromDrawer("stats")
        }
        
        DrawerItem(stringResource(id = R.string.drawer_achievements), Icons.Default.Person, currentRoute == "profile") {
            onNavigateFromDrawer("profile")
        }

        DrawerItem(stringResource(id = R.string.drawer_model_manager), Icons.Default.CloudDownload, currentRoute == "model_manager") {
            onOpenModelManager()
        }

        DrawerItem(stringResource(id = R.string.drawer_widgets), Icons.Default.Widgets, currentRoute == "widgets") {
            onNavigateFromDrawer("widgets")
        }

        DrawerItem(stringResource(id = R.string.drawer_about), Icons.Default.Info, currentRoute == "about") {
            onNavigateFromDrawer("about")
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(id = R.string.drawer_settings))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeToggleIcon(
                    icon = Icons.Default.LightMode,
                    selected = themeMode == ThemeMode.Light,
                    onClick = { appViewModel.setThemeMode(ThemeMode.Light) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                ThemeToggleIcon(
                    icon = Icons.Default.DarkMode,
                    selected = themeMode == ThemeMode.Dark,
                    onClick = { appViewModel.setThemeMode(ThemeMode.Dark) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                ThemeToggleIcon(
                    icon = Icons.Default.SettingsBrightness,
                    selected = themeMode == ThemeMode.Auto,
                    onClick = { appViewModel.setThemeMode(ThemeMode.Auto) }
                )
            }
        }
    }
}

@Composable
private fun ThemeToggleIcon(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun DrawerItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = contentColor
        )
    }
}
