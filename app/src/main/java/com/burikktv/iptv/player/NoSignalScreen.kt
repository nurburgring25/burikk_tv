package com.burikktv.iptv.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A classic TV "test card" / color-bars screen shown in place of the video
 * when a channel fails to load, instead of a plain error message — the same
 * visual language broadcast engineers have used for decades to say "we know
 * this feed is dead, here's confirmation the display itself is fine".
 */
@Composable
fun NoSignalScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFF8C8C8C)), contentAlignment = Alignment.Center) {
        GridBackground(modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(Color(0xFFF2F2F2)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1.1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.34f)
                            .fillMaxHeight(0.7f)
                            .background(Color.Black),
                    )
                }
                MoireRow(modifier = Modifier.weight(1f).fillMaxWidth())
                NumberRow(modifier = Modifier.weight(1f).fillMaxWidth())
                ColorBarRow(modifier = Modifier.weight(1.6f).fillMaxWidth())
                Box(modifier = Modifier.weight(0.35f).fillMaxWidth().background(Color.Black))
                MoireRow(modifier = Modifier.weight(1f).fillMaxWidth())
                GrayscaleRow(modifier = Modifier.weight(1f).fillMaxWidth())
                NoSignalBanner(modifier = Modifier.weight(1.3f).fillMaxWidth())
                BottomAccentRow(modifier = Modifier.weight(0.6f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GridBackground(modifier: Modifier = Modifier) {
    val gridLine = Color(0xFFA6A6A6)
    val stripeColors = listOf(
        Color(0xFF2ECC71) to 0.00f,
        Color(0xFF3B82F6) to 0.055f,
        Color(0xFFE91E8C) to 0.62f,
        Color(0xFFFF8C00) to 0.86f,
    )
    Canvas(modifier = modifier) {
        val step = 56.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(gridLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }

        val stripeWidth = 34.dp.toPx()
        val edgeInset = size.width * 0.085f
        for ((color, startFraction) in stripeColors) {
            val height = size.height * 0.22f
            val top = size.height * startFraction
            // left edge
            drawRect(color, topLeft = Offset(edgeInset, top), size = Size(stripeWidth, height))
            // right edge (mirrored)
            drawRect(
                color,
                topLeft = Offset(size.width - edgeInset - stripeWidth, top),
                size = Size(stripeWidth, height),
            )
        }
    }
}

@Composable
private fun MoireRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier.background(Color.White).padding(horizontal = 4.dp, vertical = 2.dp)) {
        val spacings = listOf(6, 3, 2, 4, 5)
        spacings.forEach { spacing ->
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp)) {
                var x = 0f
                val gap = spacing.dp.toPx()
                while (x < size.width) {
                    drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), strokeWidth = gap / 3f)
                    x += gap
                }
            }
        }
    }
}

@Composable
private fun NumberRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        (1..7).forEach { n ->
            val bg = if (n % 2 == 0) Color(0xFFBFBFBF) else Color.Black
            val fg = if (n % 2 == 0) Color.Black else Color.White
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(bg), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(text = n.toString(), color = fg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ColorBarRow(modifier: Modifier = Modifier) {
    val colors = listOf(
        Color(0xFFFFD400),
        Color(0xFF00D3D3),
        Color(0xFF2ECC71),
        Color(0xFFE91E8C),
        Color(0xFFE81E25),
        Color(0xFF1E3CE8),
    )
    Row(modifier = modifier) {
        colors.forEachIndexed { index, color ->
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(color))
            if (index == colors.lastIndex / 2) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(Color.Black))
            }
        }
    }
}

@Composable
private fun GrayscaleRow(modifier: Modifier = Modifier) {
    val shades = listOf(0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f)
    Row(modifier = modifier) {
        shades.forEach { level ->
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(level, level, level)))
        }
    }
}

@Composable
private fun NoSignalBanner(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            text = "NO SIGNAL",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            letterSpacing = 4.sp,
        )
    }
}

@Composable
private fun BottomAccentRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black))
        Box(modifier = Modifier.weight(2f).fillMaxHeight().background(Color(0xFFFFD400)))
        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE81E25)))
        Box(modifier = Modifier.weight(2f).fillMaxHeight().background(Color(0xFFFFD400)))
        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black))
    }
}
