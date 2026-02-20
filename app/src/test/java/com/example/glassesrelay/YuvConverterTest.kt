package com.example.glassesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class YuvConverterTest {

    @Test
    fun testI420ToArgb_Green() {
        val width = 2
        val height = 2

        // Y = 145, U = 54, V = 34 (approx Green)
        // Note: Bytes in Kotlin are signed. 145 fits in unsigned byte but wraps to negative in signed byte.
        val y = 145.toByte()
        val u = 54.toByte()
        val v = 34.toByte()

        val input = byteArrayOf(y, y, y, y, u, v)

        val result = YuvConverter.i420ToArgb(input, width, height)

        assertEquals(4, result.size)

        // Expected Green: ARGB(255, 0, 255, 0) -> 0xFF00FF00
        // 0xFF00FF00 in signed int is -16711936
        val expectedColor = -16711936

        for (i in result.indices) {
             assertEquals("Pixel at $i should be green", expectedColor, result[i])
        }
    }

    @Test
    fun testI420ToArgb_Black() {
        val width = 2
        val height = 2
        // Y=16, U=128, V=128 (Black)
        val y = 16.toByte()
        val u = 128.toByte()
        val v = 128.toByte()

        val input = byteArrayOf(y, y, y, y, u, v)

        val result = YuvConverter.i420ToArgb(input, width, height)

        val expectedColor = -16777216 // 0xFF000000 (Black with full alpha)

        for (i in result.indices) {
             assertEquals("Pixel at $i should be black", expectedColor, result[i])
        }
    }
}
