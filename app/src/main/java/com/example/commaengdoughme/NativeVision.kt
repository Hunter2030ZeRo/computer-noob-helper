package com.example.commaengdoughme

import androidx.annotation.Keep
import java.nio.ByteBuffer

@Keep
object NativeVision {
    init { System.loadLibrary("vision") }

    external fun preprocess(
        yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int,
        pixelStride: Int, rotation: Int, threshold: Boolean,
    ): IntArray
}
