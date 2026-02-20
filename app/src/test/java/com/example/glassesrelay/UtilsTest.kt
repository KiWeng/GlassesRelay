package com.example.glassesrelay

import org.junit.Test
import org.junit.Assert.*

class UtilsTest {

    @Test
    fun isValidRtmpUrl_validUrls() {
        assertTrue(isValidRtmpUrl("rtmp://example.com/live/key"))
        assertTrue(isValidRtmpUrl("rtmps://example.com:1935/app"))
        assertTrue(isValidRtmpUrl("rtmp://localhost/live"))
        assertTrue(isValidRtmpUrl("rtmps://192.168.1.1/live"))
    }

    @Test
    fun isValidRtmpUrl_invalidSchemes() {
        assertFalse(isValidRtmpUrl("http://example.com"))
        assertFalse(isValidRtmpUrl("https://example.com"))
        assertFalse(isValidRtmpUrl("ftp://example.com"))
        assertFalse(isValidRtmpUrl("file:///sdcard/test.mp4"))
    }

    @Test
    fun isValidRtmpUrl_invalidHosts() {
        assertFalse(isValidRtmpUrl("rtmp://"))
        assertFalse(isValidRtmpUrl("rtmps://"))
    }

    @Test
    fun isValidRtmpUrl_malformedUrls() {
        assertFalse(isValidRtmpUrl("not a url"))
        assertFalse(isValidRtmpUrl("rtmp:example.com")) // Missing //
        assertFalse(isValidRtmpUrl(""))
        assertFalse(isValidRtmpUrl(null))
        assertFalse(isValidRtmpUrl("   "))
    }

    @Test
    fun isValidRtmpUrl_invalidPorts() {
        // Port out of range (URI might throw exception or return -1 depending on impl but validity check should handle)
        // Note: URI parsing might not throw immediately on invalid port number if it's not a number, but check logic covers valid range.
        // java.net.URI is strict about parsing.
        assertFalse(isValidRtmpUrl("rtmp://example.com:70000/live"))
        assertFalse(isValidRtmpUrl("rtmp://example.com:-1/live"))
    }
}
