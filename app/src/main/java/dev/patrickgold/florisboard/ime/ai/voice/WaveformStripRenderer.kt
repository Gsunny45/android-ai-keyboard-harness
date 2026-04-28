package dev.patrickgold.florisboard.ime.ai.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Animated waveform renderer for the suggestion strip.
 *
 * Draws a real-time animated audio waveform with:
 *   - 32 vertical bars of varying heights
 *   - Smooth amplitude animation using sine wave modulation
 *   - Gradient color from primary to secondary
 *   - Recording indicator dot in the lower-right
 *
 * Intended to be embedded in FlorisBoard's suggestion strip when
 * voice recording is active.
 */
object WaveformStripRenderer {

    private const val BAR_COUNT = 32
    private const val AMPLITUDE_SPREAD = 0.7f   // how much bars vary in height
    private const val ANIMATION_DURATION_MS = 800

    /**
     * Compose Canvas that renders the animated waveform.
     * Call this in the suggestion strip composable when recording.
     */
    @Composable
    fun WaveformStrip(
        modifier: Modifier = Modifier,
        barColor: Color = Color(0xFF4A90D9),
        accentColor: Color = Color(0xFF7C4DFF),
        barWidth: Dp = 4.dp,
        barSpacing: Dp = 2.dp,
        height: Dp = 40.dp,
    ) {
        val density = LocalDensity.current
        val barWidthPx = with(density) { barWidth.toPx() }
        val barSpacingPx = with(density) { barSpacing.toPx() }
        val totalWidthPx = BAR_COUNT * (barWidthPx + barSpacingPx)

        // Continuous phase animation for smooth wave motion
        val phase = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            phase.animateTo(
                targetValue = 2f * PI.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(ANIMATION_DURATION_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                )
            )
        }

        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = 8.dp)
        ) {
            val centerY = size.height / 2f
            val maxBarHeight = size.height * 0.9f
            val startX = (size.width - totalWidthPx) / 2f

            var x = startX
            for (i in 0 until BAR_COUNT) {
                // Each bar gets its own amplitude based on sine wave at this index + phase
                val t = i.toFloat() / BAR_COUNT
                val angle = t * 2f * PI.toFloat() + phase.value
                val amplitude = (sin(angle).toFloat() * 0.5f + 0.5f) * AMPLITUDE_SPREAD + 0.2f
                val barHeight = maxBarHeight * amplitude.coerceIn(0.05f, 1.0f)

                // Interpolate color between barColor and accentColor
                val mix = t.coerceIn(0f, 1f)
                val r = barColor.red * (1 - mix) + accentColor.red * mix
                val g = barColor.green * (1 - mix) + accentColor.green * mix
                val b = barColor.blue * (1 - mix) + accentColor.blue * mix
                val color = Color(r, g, b, 0.85f)

                // Draw rounded bar
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, centerY - barHeight / 2f),
                    size = Size(barWidthPx, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidthPx / 2f),
                )

                x += barWidthPx + barSpacingPx
            }

            // Recording indicator dot (pulsing red circle in lower-right)
            drawRecordingDot(size)
        }
    }

    /**
     * Non-Compose fallback: draw waveform directly on a Canvas (for legacy
     * View-based suggestion strips). Not implemented yet — uses Compose path.
     */
    fun drawWaveformOnView(canvas: android.graphics.Canvas, width: Float, height: Float) {
        // For legacy View-based integration. Voice input uses Compose UI.
    }

    // ── Recording indicator ──────────────────────────────────────────────

    private fun DrawScope.drawRecordingDot(canvasSize: Size) {
        val dotRadius = 4.dp.toPx()
        val dotX = canvasSize.width - 20.dp.toPx()
        val dotY = canvasSize.height - 10.dp.toPx()

        // Outer glow
        drawCircle(
            color = Color(0x33FF0000),
            radius = dotRadius * 2.5f,
            center = Offset(dotX, dotY),
        )

        // Inner red dot
        drawCircle(
            color = Color(0xFFFF4444),
            radius = dotRadius,
            center = Offset(dotX, dotY),
        )
    }

    // ── Waveform path for legacy rendering ───────────────────────────────

    /**
     * Builds a smooth waveform Path from amplitude values.
     */
    fun buildWaveformPath(
        amplitudes: FloatArray,
        width: Float,
        height: Float,
        centerY: Float = height / 2f,
    ): Path {
        val path = Path()
        if (amplitudes.isEmpty()) return path

        val stepX = width / amplitudes.size
        path.moveTo(0f, centerY)

        for (i in amplitudes.indices) {
            val x = i * stepX
            val y = centerY - amplitudes[i] * height * 0.4f
            path.lineTo(x, y)
        }

        // Return to center line
        path.lineTo(width, centerY)
        return path
    }
}
