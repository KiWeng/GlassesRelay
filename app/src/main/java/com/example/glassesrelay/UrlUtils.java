package com.example.glassesrelay;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class UrlUtils {
    /**
     * Sanitizes an RTMP URL by redacting the stream key (last path segment)
     * and query parameters.
     */
    public static String sanitizeRtmpUrl(String url) {
        if (url == null || url.trim().isEmpty()) return url;

        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();

            // Only process rtmp/rtmps schemes
            if (scheme == null || !scheme.toLowerCase().startsWith("rtmp")) {
                return url;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://");

            if (uri.getUserInfo() != null) {
                sb.append("****@");
            }

            if (uri.getHost() != null) {
                sb.append(uri.getHost());
            }

            if (uri.getPort() != -1) {
                sb.append(":").append(uri.getPort());
            }

            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                String[] segments = path.split("/");
                List<String> validSegments = new ArrayList<>();
                for(String s : segments) {
                    if(!s.isEmpty()) validSegments.add(s);
                }

                if (validSegments.size() >= 2) {
                    sb.append("/");
                    for (int i = 0; i < validSegments.size() - 1; i++) {
                        sb.append(validSegments.get(i)).append("/");
                    }
                    sb.append("****");
                } else {
                    sb.append(path);
                }
            }

            if (uri.getQuery() != null) {
                sb.append("?****");
            }

            return sb.toString();

        } catch (Exception e) {
            return "rtmp://...[REDACTED]";
        }
    }
}
