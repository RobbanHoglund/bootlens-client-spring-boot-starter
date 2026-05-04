package com.bootlens.client.registration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpRegistrationTransport implements RegistrationTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;

    HttpRegistrationTransport() {
        this(HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build());
    }

    HttpRegistrationTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public RegistrationCallResult post(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        return send(request);
    }

    @Override
    public RegistrationCallResult delete(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(2))
            .DELETE()
            .build();
        return send(request);
    }

    private RegistrationCallResult send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return RegistrationCallResult.success(statusCode);
        }
        if (statusCode == 404) {
            return RegistrationCallResult.notFound(firstNonBlank(response.body(), "BootLens Server registry endpoint not found"));
        }
        return RegistrationCallResult.failure(statusCode, firstNonBlank(response.body(), "HTTP " + statusCode));
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
