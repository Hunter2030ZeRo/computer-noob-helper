package com.example.commaengdoughme

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
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
    private val setupScan: () -> Boolean = { false },
    private val onSetup: (String) -> Unit = {},
) : ImageAnalysis.Analyzer, AutoCloseable {
    val executor = Executors.newSingleThreadExecutor()
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val ocrBusy = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val closed = AtomicBoolean(false)
    private var lastFrameMs = -intervalMs
    private var lastScanMs = -250L
    private val qrScanner = lazy { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }

    init { require(intervalMs >= 250) }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (closed.get()) return
            if (setupScan()) {
                if (now - lastScanMs < 250) return
                lastScanMs = now
                val media = image.image ?: return
                val codes = Tasks.await(qrScanner.value.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)))
                codes.firstNotNullOfOrNull { it.rawValue?.takeIf { raw -> raw.length <= 4096 } }?.let { raw ->
                    mainExecutor.execute { if (!closed.get() && setupScan()) onSetup(raw) }
                }
                return // Never OCR or upload a credential QR frame.
            }
            if (diagnostics?.recording == true) { diagnostics.offer(image); return }
            hands?.offer(image)
            val effectiveInterval = if (hands?.isInteracting == true) maxOf(intervalMs, 5000L) else intervalMs
            if (now - lastFrameMs < effectiveInterval || !ocrBusy.compareAndSet(false, true)) return
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
                if (qrScanner.isInitialized()) qrScanner.value.close()
                ocrExecutor.execute { recognizer.close() }
                ocrExecutor.shutdown()
            }
            executor.shutdown()
        }
    }
}
