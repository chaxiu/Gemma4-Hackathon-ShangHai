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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import com.winter.durianai.ui.theme.IcomoonFontFamily
import kotlin.math.max
import kotlin.math.min

@Composable
fun DurianEmoji(
    modifier: Modifier = Modifier,
    size: TextUnit = 24.sp
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("\ue900", color = Color(252, 203, 53), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue901", color = Color(252, 203, 53), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue902", color = Color(159, 159, 32), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue903", color = Color(253, 242, 170), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue904", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue905", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue906", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue907", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue908", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue909", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue90a", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue90b", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue90c", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue90d", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue90e", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue90f", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue910", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue911", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue912", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
        Text("\ue913", color = Color(250, 227, 122), fontFamily = IcomoonFontFamily, fontSize = size)
    }
}

@Composable
fun DoranLoadingIndicator(
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = color.copy(alpha = 0.18f)
) {
    val shapes = remember {
        listOf(
            RoundedPolygon(
                7,
                rounding = CornerRounding(radius = 0.42f, smoothing = 1f)
            ),
            RoundedPolygon.star(
                6,
                innerRadius = 0.78f,
                rounding = CornerRounding(radius = 0.42f, smoothing = 1f)
            ),
            RoundedPolygon.rectangle(
                rounding = CornerRounding(radius = 0.48f, smoothing = 1f)
            ),
            RoundedPolygon(
                9,
                rounding = CornerRounding(radius = 0.36f, smoothing = 1f)
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
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
            )
            step = (step + 1) % shapes.size
        }
    }

    val morph = remember(step) {
        Morph(
            shapes[step],
            shapes[(step + 1) % shapes.size]
        )
    }

    val infinite = rememberInfiniteTransition(label = "doran_loading")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
        label = "rotation"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
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

    Canvas(modifier = modifier.size(indicatorSize)) {
        val canvasW = size.width
        val canvasH = size.height
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

        val scale = (side / maxDim) * pulse

        matrix.reset()
        matrix.setTranslate(-left, -top)
        matrix.postScale(scale, scale)
        matrix.postTranslate((canvasW - bw * scale) / 2f, (canvasH - bh * scale) / 2f)
        androidPath.transform(matrix)

        val strokeWidth = side * 0.12f
        fillPaint.color = backgroundColor.toArgb()
        strokePaint.color = color.toArgb()
        strokePaint.strokeWidth = strokeWidth

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.rotate(rotation, canvasW / 2f, canvasH / 2f)
            native.drawPath(androidPath, fillPaint)
            native.drawPath(androidPath, strokePaint)
            native.restore()
        }
    }
}
