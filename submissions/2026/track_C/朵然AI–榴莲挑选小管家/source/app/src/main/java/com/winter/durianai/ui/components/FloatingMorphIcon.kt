package com.winter.durianai.ui.components

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.pill
import androidx.graphics.shapes.toPath
import kotlin.math.max
import kotlin.math.min

@Composable
fun FloatingMorphIcon(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
) {
    val shapes = remember {
        listOf(
            RoundedPolygon.pill(
                width = 2.15f,
                height = 0.18f,
                smoothing = 1f
            ),
            RoundedPolygon(
                3,
                rounding = CornerRounding(radius = 0.18f, smoothing = 0.78f)
            ),
            RoundedPolygon(
                7,
                rounding = CornerRounding(radius = 0.26f, smoothing = 0.9f)
            )
        )
    }

    var step by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing)
            )
            step = (step + 1) % shapes.size
        }
    }

    val morph = remember(step) {
        Morph(shapes[step], shapes[(step + 1) % shapes.size])
    }
    val shimmer by rememberInfiniteTransition(label = "floating_morph_shimmer").animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1040, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floating_morph_scale"
    )

    val androidPath = remember { Path() }
    val matrix = remember { Matrix() }
    val bounds = remember { FloatArray(4) }
    val fillPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    }
    val strokePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    Canvas(modifier = modifier.size(size)) {
        val canvasW = this.size.width
        val canvasH = this.size.height
        val side = min(canvasW, canvasH)

        androidPath.rewind()
        morph.toPath(progress.value, androidPath)
        morph.calculateBounds(bounds, true)

        val left = bounds[0]
        val top = bounds[1]
        val right = bounds[2]
        val bottom = bounds[3]
        val bw = right - left
        val bh = bottom - top
        val maxDim = max(bw, bh).coerceAtLeast(0.0001f)
        val scale = side / maxDim * 0.78f * shimmer

        matrix.reset()
        matrix.setTranslate(-left, -top)
        matrix.postScale(scale, scale)
        matrix.postTranslate((canvasW - bw * scale) / 2f, (canvasH - bh * scale) / 2f)
        androidPath.transform(matrix)

        fillPaint.color = fillColor.toArgb()
        strokePaint.color = color.toArgb()
        strokePaint.strokeWidth = side * 0.11f

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.drawPath(androidPath, fillPaint)
            native.drawPath(androidPath, strokePaint)
        }
    }
}
