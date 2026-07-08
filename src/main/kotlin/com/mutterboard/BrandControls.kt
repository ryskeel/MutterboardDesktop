package com.mutterboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Hand-drawn replacements for Material's Switch and Slider, which read as
// stock Android. These follow the app's own language instead: outlined pills,
// warm neutrals, a plain round knob, no ripple or halo.

/**
 * Outlined pill toggle: beige track with a dark knob when off; the track
 * fills dark and the knob goes light when on. Same call shape as Material's
 * Switch so call sites swap 1:1.
 */
@Composable
fun BrandSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val width = 46.dp
    val height = 28.dp
    val knob = 16.dp
    val inset = 4.dp
    val track by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        tween(150),
    )
    val knobColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.primary,
        tween(150),
    )
    val knobOffset by animateDpAsState(
        if (checked) width - inset - knob else inset,
        tween(150),
    )
    Box(
        Modifier
            .size(width, height)
            .clip(CircleShape)
            .background(track)
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(knob)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
}

/**
 * Outlined radio matching [BrandSwitch]: a 2dp ring with a dark dot when
 * selected. Sized 48dp like Material's RadioButton so existing row layouts
 * and the 48dp content indent under it keep their geometry.
 */
@Composable
fun BrandRadio(selected: Boolean, onClick: () -> Unit) {
    val dotScale by animateFloatAsState(if (selected) 1f else 0f, tween(120))
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val r = size.minDimension / 2f
            val strokePx = 2.dp.toPx()
            drawCircle(color = outline, radius = r - strokePx / 2f, style = Stroke(strokePx))
            if (dotScale > 0f) {
                drawCircle(color = primary, radius = (r - strokePx) * 0.62f * dotScale)
            }
        }
    }
}

/**
 * Minimal slider over 0..1: a slim rounded track, dark fill up to the knob,
 * and the same plain dark knob as [BrandSwitch]. Click or drag anywhere on
 * the track; [onValueChangeFinished] fires on release, mirroring Material's
 * Slider contract.
 */
@Composable
fun BrandSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val knob = 16.dp
    val trackHeight = 6.dp
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    fun fractionAt(x: Float, totalWidth: Float): Float {
        val knobPx = with(density) { knob.toPx() }
        if (totalWidth <= knobPx) return 0f
        return ((x - knobPx / 2f) / (totalWidth - knobPx)).coerceIn(0f, 1f)
    }

    Box(
        modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { offset ->
                    onValueChange(fractionAt(offset.x, size.width.toFloat()))
                    tryAwaitRelease()
                    onValueChangeFinished()
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                ) { change, _ ->
                    change.consume()
                    onValueChange(fractionAt(change.position.x, size.width.toFloat()))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val knobX = with(density) { ((widthPx - knob.toPx()) * value.coerceIn(0f, 1f)).toDp() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Box(
            Modifier
                .width(knobX + knob / 2)
                .height(trackHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            Modifier
                .offset(x = knobX)
                .size(knob)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
