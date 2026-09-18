package com.example.commaengdoughme

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import org.junit.Assert.assertTrue
import org.junit.Test

class HandModelTest {
    @Test fun bundledModelLoadsAndBlankFrameHasNoHands() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(1).build())
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val image = BitmapImageBuilder(bitmap).build()
        try {
            assertTrue(detector.detectForVideo(image, 1).landmarks().isEmpty())
        } finally {
            image.close(); detector.close(); bitmap.recycle()
        }
    }
}
