package com.example.commaengdoughme

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class VisionResult(
    val id: String,
    val text: String,
    val capturedAtMs: Long,
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
    val headPose: HeadPose? = null,
)

class VisionAnalyzer(
    private val threshold: Boolean,
    private val intervalMs: Long,
    private val mainExecutor: Executor,
    private val onResult: (VisionResult) -> Unit,
    private val onError: (String) -> Unit,
    private val poseAt: (Long) -> HeadPose? = { null },
    private val hands: HandTracker? = null,
    private val diagnostics: TrackingDiagnostics? = null,
) : ImageAnalysis.Analyzer, AutoCloseable {
    val executor = Executors.newSingleThreadExecutor()
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val ocrBusy = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val closed = AtomicBoolean(false)
    private var lastFrameMs = -intervalMs

    init { require(intervalMs >= 250) }

    override fun analyze(image: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (closed.get()) return
            if (diagnostics?.recording == true) { diagnostics.offer(image); return }
            hands?.offer(image)
            if (now - lastFrameMs < intervalMs || !ocrBusy.compareAndSet(false, true)) return
            lastFrameMs = now
            val capturedAt = System.currentTimeMillis()
            val capturedPose = poseAt(image.imageInfo.timestamp)
            val plane = image.planes[0]
            val rotation = image.imageInfo.rotationDegrees
            val swapped = rotation == 90 || rotation == 270
            val width = if (swapped) image.height else image.width
            val height = if (swapped) image.width else image.height
            val pixels = NativeVision.preprocess(
                plane.buffer.slice(), image.width, image.height,
                plane.rowStride, plane.pixelStride, rotation, threshold,
            )
            val frame = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            bitmap = frame
            ocrExecutor.execute {
                try {
                    if (!closed.get()) {
                        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0))).text
                        val jpeg = ByteArrayOutputStream().use {
                            check(frame.compress(Bitmap.CompressFormat.JPEG, 80, it))
                            it.toByteArray()
                        }
                        val result = VisionResult(UUID.randomUUID().toString(), text, capturedAt, width, height, jpeg, capturedPose)
                        mainExecutor.execute { if (!closed.get()) onResult(result) }
                    }
                } catch (e: Exception) {
                    mainExecutor.execute { if (!closed.get()) onError("OCR 실패: ${e.javaClass.simpleName}") }
                } finally { frame.recycle(); ocrBusy.set(false) }
            }
            bitmap = null // OCR owns the copy; release the camera immediately for hand tracking.
        } catch (e: Exception) {
            ocrBusy.set(false)
            mainExecutor.execute { if (!closed.get()) onError("OCR 실패: ${e.javaClass.simpleName}") }
        } catch (e: LinkageError) {
            ocrBusy.set(false)
            mainExecutor.execute { if (!closed.get()) onError("네이티브 라이브러리 로드 실패: ABI를 확인하세요") }
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            hands?.close()
            // Let in-flight OCR finish before freeing its recognizer and bitmap.
            // Queue cleanup after the camera lane has finished submitting OCR.
            executor.execute {
                ocrExecutor.execute { recognizer.close() }
                ocrExecutor.shutdown()
            }
            executor.shutdown()
        }
    }
}
