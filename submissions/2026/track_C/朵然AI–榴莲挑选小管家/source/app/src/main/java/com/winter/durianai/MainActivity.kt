package com.winter.durianai

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winter.durianai.data.local.prefs.UserPreferencesRepository
import com.winter.durianai.floating.FloatingBallController
import com.winter.durianai.ui.AppViewModel
import com.winter.durianai.ui.AppViewModelFactory
import com.winter.durianai.ui.components.DurianEmoji
import com.winter.durianai.ui.navigation.AppNavigation
import com.winter.durianai.ui.theme.DurianaiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val startRouteFlow = MutableStateFlow<String?>(null)
    private var pendingFloatingBallLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val keepSystemSplash = AtomicBoolean(true)
        installSplashScreen().setKeepOnScreenCondition { keepSystemSplash.get() }
        super.onCreate(savedInstanceState)
        
        // Manual DI
        val userPreferencesRepository = UserPreferencesRepository(applicationContext)
        val appViewModelFactory = AppViewModelFactory(userPreferencesRepository)
        startRouteFlow.value = resolveStartRoute(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_FLOATING_BALL, false)) {
            pendingFloatingBallLaunch = !FloatingBallController.launchFrom(this)
        }

        lifecycleScope.launch {
            userPreferencesRepository.language.collect { saved ->
                val locales = when (saved.lowercase()) {
                    "zh" -> LocaleListCompat.forLanguageTags("zh")
                    "en" -> LocaleListCompat.forLanguageTags("en")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != locales.toLanguageTags()) {
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            val appViewModel: AppViewModel = viewModel(factory = appViewModelFactory)
            val themeMode by appViewModel.themeMode.collectAsState()
            val startRoute by startRouteFlow.collectAsState()
            val splashVisible = remember { mutableStateOf(true) }
            val haloAlpha = remember { Animatable(0f) }
            val haloScale = remember { Animatable(0.92f) }
            val mainAlpha = remember { Animatable(0f) }
            val mainScale = remember { Animatable(0.72f) }
            val textAlpha = remember { Animatable(0f) }
            val typedText = remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                withFrameNanos { }
                keepSystemSplash.set(false)
                haloAlpha.snapTo(0f)
                haloScale.snapTo(0.92f)
                mainAlpha.snapTo(0f)
                mainScale.snapTo(0.72f)
                textAlpha.snapTo(0f)
                typedText.value = ""

                haloAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
                haloScale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                )

                delay(140)

                launch {
                    mainAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                    )
                }
                mainScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )

                delay(220)

                textAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                )
                val full = "Doran AI"
                for (i in 1..full.length) {
                    typedText.value = full.take(i)
                    delay(70)
                }

                delay(360)
                splashVisible.value = false
            }

            DurianaiTheme(themeMode = themeMode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        appViewModel = appViewModel,
                        startRoute = startRoute,
                        onStartRouteConsumed = { startRouteFlow.value = null },
                        onOpenFloatingBall = {
                            pendingFloatingBallLaunch = !FloatingBallController.launchFrom(this@MainActivity)
                        }
                    )
                    AnimatedVisibility(
                        visible = splashVisible.value,
                        enter = fadeIn(animationSpec = tween(120)),
                        exit = fadeOut(animationSpec = tween(240))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(contentAlignment = Alignment.Center) {
                                    DurianEmoji(
                                        modifier = Modifier
                                            .graphicsLayer(
                                                scaleX = haloScale.value,
                                                scaleY = haloScale.value,
                                                alpha = haloAlpha.value * 0.22f
                                            )
                                            .blur(22.dp),
                                        size = 150.sp
                                    )
                                    DurianEmoji(
                                        modifier = Modifier.graphicsLayer(alpha = mainAlpha.value * 0.18f),
                                        size = 168.sp
                                    )
                                    Image(
                                        painter = painterResource(id = R.drawable.doran_logo),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .graphicsLayer(
                                                scaleX = mainScale.value,
                                                scaleY = mainScale.value,
                                                alpha = mainAlpha.value
                                            )
                                            .size(120.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                androidx.compose.material3.Text(
                                    text = typedText.value,
                                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.graphicsLayer(alpha = textAlpha.value)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingFloatingBallLaunch && FloatingBallController.hasOverlayPermission(this)) {
            pendingFloatingBallLaunch = false
            FloatingBallController.launchFrom(this)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        startRouteFlow.value = resolveStartRoute(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_FLOATING_BALL, false)) {
            pendingFloatingBallLaunch = !FloatingBallController.launchFrom(this)
        }
    }

    private fun resolveStartRoute(intent: android.content.Intent?): String? {
        if (intent == null) return null
        if (intent.getBooleanExtra("open_widgets", false)) return "widgets"
        return intent.getStringExtra("start_route")
    }

    companion object {
        const val EXTRA_OPEN_FLOATING_BALL = "open_floating_ball"
    }
}
