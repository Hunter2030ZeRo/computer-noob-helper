package com.example.commaengdoughme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface

/** Sensor data are immutable across camera worker/main thread boundaries. No vendor SDK required. */
class HeadTracker(context: Context, private val displayRotation: () -> Int) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    val trackingMode: String get() = if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) "자북 보정 회전 · 자기장 영향 가능" else "상대 회전 · 장시간 yaw 드리프트 가능"
    val sensorName: String get() = sensor?.name ?: "회전 센서 없음"
    @Volatile private var current: HeadPose? = null
    private val history = ArrayDeque<HeadPose>()
    private var generation = 0L
    @Volatile private var rotation = -1
    private var active = false

    @Synchronized fun start() {
        if (active || sensor == null) return
        generation++
        current = null
        history.clear()
        active = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }
    @Synchronized fun stop() {
        manager.unregisterListener(this)
        active = false
        current = null
        history.clear()
    }
    fun latest(): HeadPose? = current?.takeIf { SystemClock.elapsedRealtimeNanos() - it.timestampNs in 0..250_000_000L }
    fun displayMatches(value: Int) = rotation == value

    /** Camera REALTIME timestamps share the elapsedRealtimeNanos timebase; reject unsynchronized frames. */
    @Synchronized fun at(timestampNs: Long): HeadPose? = history.minByOrNull { kotlin.math.abs(it.timestampNs - timestampNs) }
        ?.takeIf { kotlin.math.abs(it.timestampNs - timestampNs) <= 50_000_000L }

    @Synchronized override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val screenRotation = displayRotation()
        if (screenRotation != rotation) {
            rotation = screenRotation
            generation++
            history.clear()
        }
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val remapped = FloatArray(9)
        val axes = when (screenRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, remapped)) return
        current = HeadPose(remapped.map { it.toDouble() }, event.timestamp, generation)
        history.addLast(current!!)
        while (history.size > 240) history.removeFirst()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
