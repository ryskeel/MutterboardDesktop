package com.mutterboard

import kotlin.math.PI
import kotlin.math.sin

/**
 * The brand squiggle — the same sine as the Android app's launcher icon,
 * header mark and monochrome icon (ic_launcher_foreground, ic_mutterboard_mark):
 * two and a half periods, dipping first, amplitude ~0.147 of its width and a
 * round stroke ~0.106 of its width. Shared by the header mark and the tray.
 */
object Squiggle {
    private const val PERIODS = 2.5
    private const val AMPLITUDE = 10.29f / 70f
    private const val STROKE = 7.41f / 70f

    /** Points from [x0] to [x1], centred on [midY]. */
    fun points(x0: Float, x1: Float, midY: Float, steps: Int = 120): List<Pair<Float, Float>> {
        val w = x1 - x0
        return (0..steps).map { i ->
            val t = i / steps.toFloat()
            x0 + w * t to midY + w * AMPLITUDE * sin(2 * PI * PERIODS * t).toFloat()
        }
    }

    fun stroke(width: Float): Float = width * STROKE
}
