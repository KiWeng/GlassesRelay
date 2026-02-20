package com.example.glassesrelay

import android.graphics.Bitmap

object YuvConverter {

    /**
     * Converts I420 (planar YUV) byte array to ARGB IntArray.
     *
     * @param input The I420 input byte array.
     * @param width Width of the image.
     * @param height Height of the image.
     * @return IntArray containing ARGB pixels.
     */
    fun i420ToArgb(input: ByteArray, width: Int, height: Int): IntArray {
        val size = width * height
        val argb = IntArray(size)
        var yIndex = 0
        var uIndex = size
        var vIndex = size + size / 4

        // Loop controls
        // For I420, Y is full res. U and V are half width, half height.
        // I420 layout: Y plane, then U plane, then V plane.

        for (y in 0 until height) {
            val uvRowStart = (y shr 1) * (width shr 1)
            var uRowIndex = uIndex + uvRowStart
            var vRowIndex = vIndex + uvRowStart

            for (x in 0 until width) {
                // Y
                var Y = input[yIndex].toInt() and 0xFF

                // UV
                // mapped from 2x2 block in Y to 1 pixel in U/V
                val uvOffset = (x shr 1)
                var U = input[uRowIndex + uvOffset].toInt() and 0xFF
                var V = input[vRowIndex + uvOffset].toInt() and 0xFF

                // Adjust to zero-centered
                Y -= 16
                if (Y < 0) Y = 0
                U -= 128
                V -= 128

                // Integer math for performance (approximating standard BT.601)
                // R = 1.164 * (Y - 16) + 1.596 * (V - 128)
                // G = 1.164 * (Y - 16) - 0.813 * (V - 128) - 0.391 * (U - 128)
                // B = 1.164 * (Y - 16) + 2.018 * (U - 128)

                // Scaled by 1024
                val y1192 = 1192 * Y
                var r = (y1192 + 1634 * V)
                var g = (y1192 - 833 * V - 400 * U)
                var b = (y1192 + 2066 * U)

                // Clamp and shift
                r = if (r < 0) 0 else if (r > 262143) 262143 else r
                g = if (g < 0) 0 else if (g > 262143) 262143 else g
                b = if (b < 0) 0 else if (b > 262143) 262143 else b

                // ARGB (Alpha is 255)
                argb[yIndex] = -0x1000000 or ((r shl 6) and 0xFF0000) or ((g shr 2) and 0xFF00) or ((b shr 10) and 0xFF)

                yIndex++
            }
        }
        return argb
    }

    /**
     * Converts I420 (planar YUV) byte array to Bitmap.
     *
     * @param input The I420 input byte array.
     * @param width Width of the image.
     * @param height Height of the image.
     * @return Bitmap containing the image, or null if creation failed.
     */
    fun i420ToBitmap(input: ByteArray, width: Int, height: Int): Bitmap? {
        try {
            val pixels = i420ToArgb(input, width, height)
            return Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
