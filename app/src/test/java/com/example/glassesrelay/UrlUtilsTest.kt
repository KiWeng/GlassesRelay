package com.example.glassesrelay

import org.junit.Test
import org.junit.Assert.*

class UrlUtilsTest {

    @Test
    fun testSanitizeRtmpUrl_StandardFormat() {
        assertEquals("rtmp://host/app/****", UrlUtils.sanitizeRtmpUrl("rtmp://host/app/key"))
    }

    @Test
    fun testSanitizeRtmpUrl_WithQuery() {
        assertEquals("rtmp://host/app/****?****", UrlUtils.sanitizeRtmpUrl("rtmp://host/app/key?token=abc"))
    }

    @Test
    fun testSanitizeRtmpUrl_AppOnly() {
        assertEquals("rtmp://host/app", UrlUtils.sanitizeRtmpUrl("rtmp://host/app"))
    }

    @Test
    fun testSanitizeRtmpUrl_RtmpsWithPort() {
        assertEquals("rtmps://host:443/app/****", UrlUtils.sanitizeRtmpUrl("rtmps://host:443/app/key"))
    }

    @Test
    fun testSanitizeRtmpUrl_WithUserInfo() {
        // user:pass should be redacted
        // Note: URI parsing might handle user info differently depending on input
        // rtmp://user:pass@host/app/key -> user:pass is userInfo
        assertEquals("rtmp://****@host/app/****", UrlUtils.sanitizeRtmpUrl("rtmp://user:pass@host/app/key"))
    }

    @Test
    fun testSanitizeRtmpUrl_MultiSegmentPath() {
        assertEquals("rtmp://host/app/instance/****", UrlUtils.sanitizeRtmpUrl("rtmp://host/app/instance/key"))
    }

    @Test
    fun testSanitizeRtmpUrl_IPAddress() {
        assertEquals("rtmp://192.168.1.5/live/****", UrlUtils.sanitizeRtmpUrl("rtmp://192.168.1.5/live/test"))
    }

    @Test
    fun testSanitizeRtmpUrl_NonRtmp() {
        assertEquals("http://example.com/path", UrlUtils.sanitizeRtmpUrl("http://example.com/path"))
    }

    @Test
    fun testSanitizeRtmpUrl_Empty() {
        assertEquals("", UrlUtils.sanitizeRtmpUrl(""))
    }

    @Test
    fun testSanitizeRtmpUrl_Malformed() {
        // A truly malformed URL that URI parser rejects
        // e.g. spaces in host or invalid chars
        val malformed = "rtmp://host with space/app/key"
        assertEquals("rtmp://...[REDACTED]", UrlUtils.sanitizeRtmpUrl(malformed))
    }
}
