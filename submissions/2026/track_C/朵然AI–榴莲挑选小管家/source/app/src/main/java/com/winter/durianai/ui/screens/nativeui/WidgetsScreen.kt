package com.winter.durianai.ui.screens.nativeui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winter.durianai.R
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.ui.screens.agent.AgentChatViewModel
import com.winter.durianai.widgets.BadgesWidgetProvider
import com.winter.durianai.widgets.Badge1WidgetProvider
import com.winter.durianai.widgets.Badge2WidgetProvider
import com.winter.durianai.widgets.Badge3WidgetProvider
import com.winter.durianai.widgets.Badge4WidgetProvider
import com.winter.durianai.widgets.Badge5WidgetProvider
import com.winter.durianai.widgets.DailyAdviceWidgetProvider
import com.winter.durianai.widgets.WidgetPinResultReceiver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetsScreen(
    agentChatViewModel: AgentChatViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val prefs = remember { UserPreferencesRepository(context) }
    val advice by prefs.widgetDailyAdvice.collectAsState(initial = null)
    val reminder by prefs.widgetDailyReminder.collectAsState(initial = null)
    val totalReports by prefs.reportTotalCount.collectAsState(initial = 0)
    val lowCount by prefs.reportLowCount.collectAsState(initial = 0)
    val streakDays by prefs.reportStreakDays.collectAsState(initial = 0)
    val level1Count by prefs.reportLevel1Count.collectAsState(initial = 0)
    val latestWidgetScore by prefs.widgetLatestReportScore.collectAsState(initial = null)
    val latestWidgetLevel by prefs.widgetLatestReportLevel.collectAsState(initial = null)
    val latestWidgetVariety by prefs.widgetLatestReportVariety.collectAsState(initial = null)
    val latestWidgetSuggestion by prefs.widgetLatestReportSuggestion.collectAsState(initial = null)

    val appWidgetManager = remember { AppWidgetManager.getInstance(context) }
    val canPin = remember { appWidgetManager.isRequestPinAppWidgetSupported }

    fun requestPin(provider: Class<*>) {
        if (!canPin) {
            Toast.makeText(context, context.getString(R.string.widgets_add_not_supported), Toast.LENGTH_SHORT).show()
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.widgets_add_not_supported)) }
            return
        }
        val component = ComponentName(context, provider)
        if (!appWidgetManager.isRequestPinAppWidgetSupported) {
            Toast.makeText(context, "当前桌面不支持一键添加，请长按桌面 → 小组件 → 选择“朵然”添加", Toast.LENGTH_LONG).show()
            scope.launch {
                snackbarHostState.showSnackbar(
                    "当前桌面不支持一键添加，请长按桌面 → 小组件 → 选择“朵然”添加"
                )
            }
            return
        }
        val callbackIntent = Intent(context, WidgetPinResultReceiver::class.java).apply {
            action = "com.winter.durianai.WIDGET_PINNED"
            putExtra("provider", provider.name)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val callback = PendingIntent.getBroadcast(context, provider.name.hashCode(), callbackIntent, flags)
        val requested = appWidgetManager.requestPinAppWidget(component, null, callback)
        if (requested) {
            val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                "已发送到桌面，请在桌面确认添加"
            } else {
                "已发送到桌面"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            scope.launch { snackbarHostState.showSnackbar(msg) }
        } else {
            val msg = "未能发起一键添加，请长按桌面 → 小组件 → 选择“朵然”添加"
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            scope.launch {
                snackbarHostState.showSnackbar(
                    "未能发起一键添加，请长按桌面 → 小组件 → 选择“朵然”添加"
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.widgets_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(stringResource(id = R.string.widgets_daily_title), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🍈", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(id = R.string.widgets_daily_title), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        if (latestWidgetScore != null && latestWidgetLevel != null) {
                            "最近评分 ${latestWidgetScore}分 · Level ${latestWidgetLevel} · ${latestWidgetVariety ?: "未标注品种"}"
                        } else {
                            advice ?: stringResource(id = R.string.widget_daily_fallback_advice)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            latestWidgetSuggestion?.takeIf { it.isNotBlank() }
                                ?: reminder
                                ?: stringResource(id = R.string.widget_daily_fallback_reminder),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(onClick = { requestPin(DailyAdviceWidgetProvider::class.java) }) {
                        Text(stringResource(id = R.string.widgets_add_to_home))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(id = R.string.widgets_badges_title), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🍈", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(stringResource(id = R.string.widgets_badges_title), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "$streakDays / $totalReports",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    val badges = remember {
                        listOf(
                            R.drawable.meiriyijian,
                            R.drawable.paileixianfeng,
                            R.drawable.pinzhibushou,
                            R.drawable.tujiandaren,
                            R.drawable.kuangreguofen
                        )
                    }

                    val earned = remember(streakDays, totalReports, lowCount, level1Count) {
                        listOf(
                            streakDays >= 3,
                            lowCount >= 1,
                            level1Count >= 5,
                            totalReports >= 10,
                            totalReports >= 20
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        badges.take(3).forEachIndexed { index, resId ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .alpha(if (earned[index]) 1f else 0.35f)
                            ) {
                                Image(
                                    painter = painterResource(id = resId),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        badges.drop(3).forEachIndexed { index, resId ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .alpha(if (earned[index + 3]) 1f else 0.35f)
                            ) {
                                Image(
                                    painter = painterResource(id = resId),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(onClick = { requestPin(BadgesWidgetProvider::class.java) }) {
                        Text(stringResource(id = R.string.widgets_add_to_home))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("单徽章", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    val badgeItems = remember {
                        listOf(
                            Triple(R.drawable.meiriyijian, "每日一鉴", Badge1WidgetProvider::class.java),
                            Triple(R.drawable.paileixianfeng, "排雷先锋", Badge2WidgetProvider::class.java),
                            Triple(R.drawable.pinzhibushou, "品质捕手", Badge3WidgetProvider::class.java),
                            Triple(R.drawable.tujiandaren, "图鉴达人", Badge4WidgetProvider::class.java),
                            Triple(R.drawable.kuangreguofen, "狂热果粉", Badge5WidgetProvider::class.java)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        badgeItems.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                row.forEach { (resId, title, provider) ->
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(MaterialTheme.colorScheme.surface)
                                            ) {
                                                Image(
                                                    painter = painterResource(id = resId),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                title,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Button(
                                                onClick = { requestPin(provider) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(stringResource(id = R.string.widgets_add_to_home))
                                            }
                                        }
                                    }
                                }
                                if (row.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
