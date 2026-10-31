package com.ratatoskr.shared.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The logo as a brass-rimmed medallion for the unlock / setup screens:
 * the icon clipped to a circle, a gradient brass rim, a faint outer ring,
 * and a warm halo behind it that breathes slowly. It eases in (fade and a
 * slight scale-up) the first time it's shown. Purely decorative.
 */
@Composable
internal fun BrandEmblem(painter: Painter, size: Dp = 104.dp, modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing)) }

    val breath by rememberInfiniteTransition(label = "emblem-glow").animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emblem-glow-alpha",
    )

    Box(
        modifier = modifier
            .size(size * 1.55f)
            .graphicsLayer {
                alpha = appear.value
                val s = 0.9f + 0.1f * appear.value
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.Center,
    ) {
        // Halo and the faint outer ring.
        Canvas(Modifier.matchParentSize()) {
            val r = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to RatatoskrColors.BrassBright.copy(alpha = 0.38f * breath),
                    0.45f to RatatoskrColors.Brass.copy(alpha = 0.17f * breath),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = r,
                ),
                radius = r,
            )
            drawCircle(
                color = RatatoskrColors.Brass.copy(alpha = 0.22f),
                radius = size.toPx() / 2f + 9.dp.toPx(),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Image(
            painter = painter,
            contentDescription = "Ratatoskr logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            RatatoskrColors.BrassBright,
                            RatatoskrColors.Brass.copy(alpha = 0.55f),
                            RatatoskrColors.BrassBright,
                            RatatoskrColors.Brass.copy(alpha = 0.55f),
                            RatatoskrColors.BrassBright,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )
    }
}
