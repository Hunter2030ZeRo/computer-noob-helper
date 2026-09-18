package com.example.commaengdoughme

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class HandRgbTest {
    @Test fun paddedLumaAndInterleavedChromaProduceBlackAndWhite() {
        val y = ByteBuffer.allocateDirect(6).apply { put(byteArrayOf(16, 235.toByte(), 99, 99, 235.toByte(), 16)); rewind() }
        val u = ByteBuffer.allocateDirect(1).apply { put(128.toByte()); rewind() }
        val v = ByteBuffer.allocateDirect(1).apply { put(128.toByte()); rewind() }
        val rgb = NativeVision.handRgb(y, u, v, 2, 2, intArrayOf(4, 1, 2, 2, 2, 2), 2, 2)
        assertArrayEquals(intArrayOf(0xff000000.toInt(), -1, -1, 0xff000000.toInt()), rgb)
    }
    @Test fun rejectsTruncatedChromaBeforeReadingIt() {
        try {
            NativeVision.handRgb(ByteBuffer.allocateDirect(16), ByteBuffer.allocateDirect(1),
                ByteBuffer.allocateDirect(4), 4, 4, intArrayOf(4,1,2,1,2,1), 2, 2)
            fail("Truncated chroma must not be read")
        } catch (_: IllegalArgumentException) { }
    }
}
