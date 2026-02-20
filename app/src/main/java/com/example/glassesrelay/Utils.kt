package com.example.glassesrelay

import java.net.URI

/**
 * Validates if the given URL is a valid RTMP or RTMPS URL.
 *
 * @param url The URL string to validate.
 * @return True if the URL is valid, false otherwise.
 */
fun isValidRtmpUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) {
        return false
    }

    return try {
        val uri = URI(url)
        val scheme = uri.scheme
        val host = uri.host
        val port = uri.port

        if (scheme == null || host == null) {
            return false
        }

        // Check scheme (rtmp or rtmps)
        if (!scheme.equals("rtmp", ignoreCase = true) &&
            !scheme.equals("rtmps", ignoreCase = true)) {
            return false
        }

        // Basic host validation (e.g. not empty) - URI.host handles this mostly but empty string check is good
        if (host.isEmpty()) {
            return false
        }

        // Port validation if present
        if (port != -1 && (port < 0 || port > 65535)) {
            return false
        }

        true
    } catch (e: Exception) {
        // Parsing failed or other error
        false
    }
}
