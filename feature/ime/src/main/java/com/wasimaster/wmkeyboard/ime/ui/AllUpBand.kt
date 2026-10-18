package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.ime.AllUpCountdown

/** How tall the all-thumbs-up band is, along the strip's bottom edge. */
private val AllUpBandHeight = 2.dp

/**
 * The two-thumb all-thumbs-up pause, made visible (#747): a band along the
 * bottom of the strip that shrinks from both ends towards the middle over the
 * pause, and is gone when the word is typed or a thumb lands. Drawn over the
 * strip rather than as a row of its own, so it moves nothing; the shrinking is
 * read in the draw phase alone, so it recomposes nothing either. Under reduced
 * motion it stays whole for the length of the pause.
 */
@Composable
internal fun Modifier.allUpBand(countdown: AllUpCountdown?, reduceMotion: Boolean): Modifier {
    if (countdown == null) return this
    val color = LocalKbTheme.current.accent
    val left = remember(countdown.id) { Animatable(1f) }
    LaunchedEffect(countdown.id, reduceMotion) {
        if (!reduceMotion) left.animateTo(0f, tween(countdown.durationMs.toInt(), easing = LinearEasing))
    }
    return drawWithContent {
        drawContent()
        val height = AllUpBandHeight.toPx()
        val width = size.width * left.value
        drawRect(color, topLeft = Offset((size.width - width) / 2f, size.height - height), size = Size(width, height))
    }
}
