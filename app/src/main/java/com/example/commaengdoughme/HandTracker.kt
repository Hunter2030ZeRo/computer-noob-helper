package com.example.commaengdoughme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Bounded RGB inference lane, independent of the slower OCR lane. No camera frame is retained. */
class HandTracker(
    private val context: Context,
    private val main: Executor,
    private val enabled: () -> Boolean,
    private val session: () -> Long,
    private val onGesture: (HandGesture) -> Unit,
    private val onStatus: (String) -> Unit,
    private val previewEnabled: () -> Boolean = { false },
    private val onPreview: (Bitmap, String) -> Unit = { _, _ -> },
    private val onObservation: (HandObservation) -> Unit = {},
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val decoder = HandGestureDecoder()
    private var detector: HandLandmarker? = null
    private var lastFrame = -100L
    private var decoderSession = -1L
    private var lastPreview = -1000L
    @Volatile private var failed = false

    fun offer(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (closed.get() || failed || !enabled() || now - lastFrame < 100 || !busy.compareAndSet(false, true)) return
        lastFrame = now
        val inputSession = session()
        val bitmap = try { rgbFrame(image) } catch (_: Exception) {
            busy.set(false)
            publish("손 영상 변환 실패")
            return
        }
        try {
            executor.execute {
                try {
                    if (!enabled() || closed.get()) { decoder.reset(); return@execute }
                    if (decoderSession != inputSession) { decoder.reset(); decoderSession = inputSession }
                    val task = detector ?: HandLandmarker.createFromOptions(context,
                        HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                            .setRunningMode(RunningMode.VIDEO).setNumHands(1)
                            .setMinHandDetectionConfidence(.5f).setMinHandPresenceConfidence(.5f)
                            .setMinTrackingConfidence(.5f).build()).also { detector = it }
                    val aspect = bitmap.height.toFloat() / bitmap.width
                    val input = BitmapImageBuilder(bitmap).build()
                    val result = try { task.detectForVideo(input, now) } finally { input.close() }
                    // Account for image aspect ratio before measuring palm-relative distances.
                    val points = result.landmarks().firstOrNull()?.map { HandPoint(it.x(), it.y() * aspect) }.orEmpty()
                    val latency = SystemClock.uptimeMillis() - now
                    val gesture = if (latency < 450) decoder.update(points, now) else { decoder.reset(); null }
                    val preview = if (previewEnabled() && now - lastPreview >= 1000) {
                        lastPreview = now
                        bitmap.copy(Bitmap.Config.ARGB_8888, true).also { copy ->
                            val canvas = android.graphics.Canvas(copy)
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.GREEN; strokeWidth = 3f
                            }
                            result.landmarks().firstOrNull()?.let { joints ->
                                for (chain in listOf(listOf(0,1,2,3,4), listOf(0,5,6,7,8), listOf(5,9,10,11,12), listOf(9,13,14,15,16), listOf(13,17,18,19,20), listOf(0,17))) {
                                    chain.zipWithNext().forEach { (a,b) -> canvas.drawLine(joints[a].x()*copy.width, joints[a].y()*copy.height,
                                        joints[b].x()*copy.width, joints[b].y()*copy.height, paint) }
                                }
                                joints.forEach { canvas.drawCircle(it.x()*copy.width, it.y()*copy.height, 4f, paint) }
                            }
                        }
                    } else null
                    val detail = "${bitmap.width}×${bitmap.height} · ${latency}ms · 관절 ${points.size} · ${decoder.hint}"
                    val observation = HandObservation(result.landmarks().firstOrNull()?.map {
                        ScreenPoint(it.x(), it.y())
                    }.orEmpty(), now, latency, decoder.hint, gesture)
                    main.execute {
                        if (!closed.get() && enabled() && session() == inputSession) {
                            onStatus(if (latency >= 450) "손 처리 지연" else if (points.isEmpty()) "손 대기" else "손 감지")
                            onObservation(observation)
                            if (preview != null && previewEnabled()) onPreview(preview, detail)
                            if (gesture != null && SystemClock.uptimeMillis() - now < 700) onGesture(gesture)
                        }
                    }
                } catch (_: Exception) {
                    failed = true
                    publish("손 인식 실패 · 카메라 재연결")
                } catch (_: LinkageError) {
                    failed = true
                    publish("손 인식 미지원 ABI")
                } finally {
                    bitmap.recycle()
                    busy.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            bitmap.recycle(); busy.set(false)
        }
    }

    private fun publish(message: String) = main.execute { if (!closed.get()) onStatus(message) }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.execute { detector?.close(); detector = null; decoder.reset() }
            executor.shutdown()
        }
    }

    /** Downsample YUV before inference; preserve color and row/pixel strides. OCR keeps its own grayscale input. */
    private fun rgbFrame(image: ImageProxy): Bitmap {
        val scale = max(image.width, image.height) / 512f
        val width = (image.width / max(1f, scale)).toInt().coerceAtLeast(1)
        val height = (image.height / max(1f, scale)).toInt().coerceAtLeast(1)
        val planes = image.planes
        require(planes.size == 3)
        val buffers = planes.map { it.buffer.slice() }
        fun sample(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            return buffers[plane].get(y * p.rowStride + x * p.pixelStride).toInt() and 255
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x * image.width / width
            val sy = y * image.height / height
            val luma = (sample(0, sx, sy) - 16).coerceAtLeast(0)
            val u = sample(1, sx / 2, sy / 2) - 128
            val v = sample(2, sx / 2, sy / 2) - 128
            val r = ((298 * luma + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luma - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luma + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (image.imageInfo.rotationDegrees == 0) return source
        return try {
            Bitmap.createBitmap(source, 0, 0, width, height, Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, false)
        } finally { source.recycle() }
    }
}
