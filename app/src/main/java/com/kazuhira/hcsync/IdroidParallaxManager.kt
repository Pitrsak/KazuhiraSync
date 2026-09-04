package com.kazuhira.hcsync

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View

/**
 * Handles subtle 3D holographic tilt and parallax reactions to device orientation/gyroscope,
 * mimicking the floating AR display of the MGSV iDroid and iOS-style parallax.
 */
class IdroidParallaxManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val isRotationVector = rotationSensor?.type == Sensor.TYPE_ROTATION_VECTOR

    // Views with their individual parallax depth multipliers
    private val targetViews = mutableListOf<ParallaxTarget>()

    // Adaptive neutral resting angles (dynamically centers to how user naturally holds the phone)
    private var basePitch = 0f
    private var baseRoll = 0f
    private var hasBaseline = false

    // Filtered smooth tilt values
    private var smoothRoll = 0f
    private var smoothPitch = 0f

    data class ParallaxTarget(
        val view: View,
        val translationFactor: Float, // Max translation in px
        val rotationFactor: Float     // Max rotation in degrees
    )

    fun registerView(view: View, translationDp: Float = 14f, rotationDeg: Float = 4f) {
        val density = context.resources.displayMetrics.density
        view.cameraDistance = 8000f * density
        targetViews.add(ParallaxTarget(view, translationDp * density, rotationDeg))
    }

    fun start() {
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        // Reset registered views back to neutral
        for (target in targetViews) {
            target.view.animate()
                .translationX(0f)
                .translationY(0f)
                .rotationX(0f)
                .rotationY(0f)
                .setDuration(250)
                .start()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (roll, pitch) = if (isRotationVector) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            Pair(orientation[2], orientation[1]) // roll, pitch in radians
        } else {
            // Gravity / Accelerometer fallback: normalize to approximate radians
            val gx = (event.values[0] / 9.8f).coerceIn(-1f, 1f)
            val gy = (event.values[1] / 9.8f).coerceIn(-1f, 1f)
            Pair(gx, gy)
        }

        if (!hasBaseline) {
            baseRoll = roll
            basePitch = pitch
            hasBaseline = true
        } else {
            // Adaptive drift: slowly converge baseline so holding at any angle doesn't stay pegged
            baseRoll += (roll - baseRoll) * 0.02f
            basePitch += (pitch - basePitch) * 0.02f
        }

        // Calculate delta from adaptive baseline and clamp to avoid extreme shifts
        val deltaRoll = (roll - baseRoll).coerceIn(-0.35f, 0.35f)
        val deltaPitch = (pitch - basePitch).coerceIn(-0.35f, 0.35f)

        // Smooth low-pass filter (lerp) for 60/120fps fluid response with zero jitter
        smoothRoll += (deltaRoll - smoothRoll) * 0.18f
        smoothPitch += (deltaPitch - smoothPitch) * 0.18f

        // Apply 3D perspective and parallax translation to registered cards
        for (target in targetViews) {
            target.view.translationX = -smoothRoll * target.translationFactor
            target.view.translationY = -smoothPitch * target.translationFactor
            target.view.rotationY = -smoothRoll * target.rotationFactor
            target.view.rotationX = smoothPitch * target.rotationFactor
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
