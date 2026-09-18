package com.example.commaengdoughme

import androidx.annotation.Keep
import java.nio.ByteBuffer

@Keep
object NativeVision {
    init { System.loadLibrary("vision") }
    external fun handRgb(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        width: Int, height: Int, strides: IntArray, outWidth: Int, outHeight: Int): IntArray

    external fun preprocess(
        yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int,
        pixelStride: Int, rotation: Int, threshold: Boolean,
    ): IntArray
}
