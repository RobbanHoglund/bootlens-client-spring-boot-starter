package com.bootlens.client.diagnostics;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.web.filter.OncePerRequestFilter;

class BootLensEndpointTimingFilter extends OncePerRequestFilter {

    static final String DURATION_HEADER = "X-BootLens-Endpoint-Duration-Ms";
    static final String NAME_HEADER = "X-BootLens-Endpoint-Name";

    private final BootLensDiagnosticsProperties properties;
    private final String basePath;

    BootLensEndpointTimingFilter(BootLensDiagnosticsProperties properties, WebEndpointProperties webEndpointProperties) {
        this.properties = properties;
        this.basePath = normalizeBasePath(webEndpointProperties == null ? null : webEndpointProperties.getBasePath());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEndpointTimingHeaderEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return true;
        }
        if ("/".equals(basePath)) {
            return false;
        }
        return !path.startsWith(basePath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        long startedNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            response.setHeader(DURATION_HEADER, Long.toString(durationMs));
            response.setHeader(NAME_HEADER, endpointName(request));
        }
    }

    private String endpointName(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return "unknown";
        }
        String relative = path;
        if (!"/".equals(basePath) && path.startsWith(basePath)) {
            relative = path.substring(basePath.length());
        }
        relative = relative.replaceAll("^/+", "");
        if (relative.isBlank()) {
            return "root";
        }
        return relative.replace('/', ':').toLowerCase(Locale.ROOT);
    }

    private static String normalizeBasePath(String value) {
        if (value == null || value.isBlank() || "/".equals(value)) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }
}
