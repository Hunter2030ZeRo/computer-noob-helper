package com.example.commaengdoughme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded local input probe, NOT a VIO estimator or a calibrated VIO dataset. */
class TrackingDiagnostics(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotations = listOfNotNull(sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR))
    private val samples = StringBuilder()
    private val frames = ArrayList<Pair<String, ByteArray>>()
    private val frameRows = StringBuilder()
    private var started = 0L
    private var lastFrame = 0L
    private var count = 0
    private var bytes = 0
    private var camera = "unknown"
    private var realtime = false
    private var recordedCamera = "unknown"
    private var recordedRealtime = false
    private var reason = "미시작"
    @Volatile var recording = false
        private set

    @Synchronized fun cameraInfo(id: String, timestampsRealtime: Boolean) {
        if (recording) stop("카메라 변경")
        camera = id; realtime = timestampsRealtime
    }

    @Synchronized fun start() {
        stop("새 기록")
        samples.clear(); frames.clear(); frameRows.clear(); count = 0; bytes = 0; lastFrame = 0
        recordedCamera = camera; recordedRealtime = realtime
        started = SystemClock.elapsedRealtimeNanos()
        recording = true; reason = "기록 중"
        val a = accel?.let { sensors.registerListener(this, it, 10000) } ?: false
        val g = gyro?.let { sensors.registerListener(this, it, 10000) } ?: false
        rotations.forEach { sensors.registerListener(this, it, 20000) }
        if (!a || !g) reason = "기록 중 · 원시 IMU 일부 없음"
    }

    @Synchronized fun stop(message: String = "기록 완료") {
        if (recording) { recording = false; reason = message; sensors.unregisterListener(this) }
    }

    @Synchronized fun status(): String {
        if (recording && SystemClock.elapsedRealtimeNanos() - started >= 15_000_000_000L) stop()
        return "$reason · IMU $count · 영상 ${frames.size}\n가속도: ${accel?.name ?: "없음"}\n자이로: ${gyro?.name ?: "없음"}\n카메라 시계: ${if (realtime) "REALTIME" else "UNKNOWN — IMU 동기화 미확인"}\nVIO 미구현 · 회전 추적만 사용"
    }

    @Synchronized override fun onSensorChanged(event: SensorEvent) {
        if (!recording) return
        if (SystemClock.elapsedRealtimeNanos() - started >= 15_000_000_000L || count >= 6000) { stop(); return }
        if (event.values.size < 3) return
        samples.append("${event.timestamp},${event.sensor.type},${event.values[0]},${event.values[1]},${event.values[2]},${event.values.getOrNull(3) ?: ""},${event.accuracy}\n")
        count++
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Copies only unrotated luminance; never retains a CameraX buffer. */
    @Synchronized fun offer(image: ImageProxy) {
        if (!recording) return
        val arrival = SystemClock.elapsedRealtimeNanos()
        if (arrival - started >= 15_000_000_000L) { stop(); return }
        if (arrival - lastFrame < 100_000_000L) return
        lastFrame = arrival
        val stride = ((maxOf(image.width, image.height) + 639) / 640).coerceAtLeast(1)
        val w = (image.width + stride - 1) / stride
        val h = (image.height + stride - 1) / stride
        val header = "P5\n$w $h\n255\n".toByteArray(Charsets.US_ASCII)
        if (bytes + w * h + header.size > 24 * 1024 * 1024 || frames.size >= 150) { stop("기록 용량 한도"); return }
        val data = ByteArray(header.size + w * h)
        header.copyInto(data)
        val plane = image.planes[0]
        val buffer = plane.buffer.slice()
        for (y in 0 until h) for (x in 0 until w) {
            data[header.size + y * w + x] = buffer.get(y * stride * plane.rowStride + x * stride * plane.pixelStride)
        }
        val path = "frames/${frames.size}.pgm"
        frames.add(path to data); bytes += data.size
        frameRows.append("${image.imageInfo.timestamp},$arrival,$path,${image.width},${image.height},$stride,${image.imageInfo.rotationDegrees}\n")
    }

    @Synchronized fun hasData() = count > 0 || frames.isNotEmpty()

    /** Export a stopped snapshot on a worker. No tokens, OCR text, or automatic upload. */
    fun export(output: OutputStream) {
        val entries = synchronized(this) {
        check(!recording)
        val metadata = JSONObject().put("schema", 1).put("purpose", "input_diagnostics_not_calibrated_vio")
            .put("camera_id", recordedCamera).put("camera_timestamp_realtime", recordedRealtime)
            .put("accelerometer", accel?.name ?: JSONObject.NULL).put("gyroscope", gyro?.name ?: JSONObject.NULL)
            .put("rotation_sensors", rotations.joinToString { "${it.type}:${it.name}" })
            .put("started_elapsed_realtime_ns", started).put("stop_reason", reason)
            .put("intrinsics_calibrated", false).put("camera_imu_extrinsics_calibrated", false)
            .put("frame_sampling", "at most 10 Hz; integer subsampling, no rotation; OCR/hand inference paused")
            .put("imu_units", "accelerometer m/s^2 including gravity; gyroscope rad/s; Android device axes")
        listOf(
            "metadata.json" to metadata.toString(2).toByteArray(),
            "imu.csv" to ("timestamp_ns,type,x,y,z,w_optional,accuracy\n$samples").toByteArray(),
            "frames.csv" to ("timestamp_ns,arrival_elapsed_ns,path,source_width,source_height,sample_stride,rotation_degrees\n$frameRows").toByteArray()
        ) + frames.toList()
        }
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
    }
}
