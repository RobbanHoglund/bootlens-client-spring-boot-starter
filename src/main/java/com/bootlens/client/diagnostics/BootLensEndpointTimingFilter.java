package com.bootlens.client.diagnostics;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponseWrapper;
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
        TimingResponseWrapper timingResponse = new TimingResponseWrapper(response, startedNanos, endpointName(request));
        try {
            filterChain.doFilter(request, timingResponse);
        }
        finally {
            timingResponse.writeTimingHeaders();
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
        if (value == null || value.isBlank()) {
            return "/actuator";
        }
        if ("/".equals(value)) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static final class TimingResponseWrapper extends HttpServletResponseWrapper {

        private final long startedNanos;
        private final String endpointName;
        private boolean timingHeadersWritten;

        private TimingResponseWrapper(HttpServletResponse response, long startedNanos, String endpointName) {
            super(response);
            this.startedNanos = startedNanos;
            this.endpointName = endpointName;
        }

        @Override
        public void flushBuffer() throws IOException {
            writeTimingHeaders();
            super.flushBuffer();
        }

        @Override
        public void sendError(int sc) throws IOException {
            writeTimingHeaders();
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            writeTimingHeaders();
            super.sendError(sc, msg);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            writeTimingHeaders();
            super.sendRedirect(location);
        }

        private void writeTimingHeaders() {
            if (timingHeadersWritten || isCommitted()) {
                return;
            }
            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            setHeader(DURATION_HEADER, Long.toString(durationMs));
            setHeader(NAME_HEADER, endpointName);
            timingHeadersWritten = true;
        }
    }
}
