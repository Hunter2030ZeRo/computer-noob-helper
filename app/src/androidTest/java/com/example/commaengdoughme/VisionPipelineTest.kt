package com.example.commaengdoughme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VisionPipelineTest {
    @Test fun nativeStrideRotationAndValidation() {
        // Padded rows, non-unit pixel stride, and no padding after the final pixel.
        val bytes = ByteBuffer.allocateDirect(11)
        byteArrayOf(10, 0, 20, 0, 30, 0, 40, 0, 50, 0, 60).forEach { bytes.put(it) }
        bytes.flip()
        val expected = mapOf(
            0 to intArrayOf(10, 20, 30, 40, 50, 60),
            90 to intArrayOf(40, 10, 50, 20, 60, 30),
            180 to intArrayOf(60, 50, 40, 30, 20, 10),
            270 to intArrayOf(30, 60, 20, 50, 10, 40),
        )
        expected.forEach { (rotation, values) ->
            val pixels = NativeVision.preprocess(bytes, 3, 2, 6, 2, rotation, false)
            assertArrayEquals(values, pixels.map { it and 255 }.toIntArray())
            assertTrue(pixels.all { it ushr 24 == 255 })
        }
        val binary = NativeVision.preprocess(bytes, 3, 2, 6, 2, 0, true)
        assertTrue(binary.all { (it and 255) in listOf(0, 255) })
        for (bad in listOf(ByteBuffer.allocateDirect(10), ByteBuffer.allocate(11))) {
            try {
                NativeVision.preprocess(bad, 3, 2, 6, 2, 0, false)
                fail("Invalid buffer accepted")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            NativeVision.preprocess(bytes, 3, 2, 6, 2, 45, false)
            fail("Invalid rotation accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun nativeToBundledOcrAndAgentPayload() {
        val source = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("HELLO 123", 30f, 125f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 80f
        })
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val y = ByteBuffer.allocateDirect(pixels.size)
        pixels.forEach { y.put((it and 255).toByte()) }
        y.flip()
        val processed = Bitmap.createBitmap(NativeVision.preprocess(y, 800, 200, 800, 1, 0, false),
            800, 200, Bitmap.Config.ARGB_8888)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(processed, 0)), 30, TimeUnit.SECONDS).text
            assertTrue("OCR result: $text", text.contains("HELLO") && text.contains("123"))
            val result = VisionResult("test-id", text, 123L, 800, 200, byteArrayOf(1, 2, 3))
            val plain = JSONObject(AgentClient.payload(result, false))
            assertEquals(text, plain.getString("text"))
            assertFalse(plain.has("image_base64"))
            val withImage = JSONObject(AgentClient.payload(result, true))
            assertEquals("AQID", withImage.getString("image_base64"))
            assertEquals(0, withImage.getInt("image_rotation_degrees"))
        } finally {
            recognizer.close()
            source.recycle()
            processed.recycle()
        }
    }
}
