package com.tpo.suby.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ResendEmailService {

    private static final URI RESEND_ENDPOINT = URI.create("https://api.resend.com/emails");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void send(String to, String subject, String html) {
        send(defaultFrom(), to, subject, html);
    }

    public void send(String from, String to, String subject, String html) {
        String apiKey = System.getenv("RESEND_API_KEY");
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("re_xxxxxxxxx")) {
            throw new IllegalStateException("Configurá resend.api-key con tu clave real de Resend.");
        }

        String payload = json(Map.of(
                "from", from,
                "to", List.of(to),
                "subject", subject,
                "html", html
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(RESEND_ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Resend error {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("No se pudo enviar el correo con Resend.");
            }
        } catch (Exception e) {
            throw new RuntimeException("No se pudo enviar el correo con Resend.", e);
        }
    }

    private String defaultFrom() {
        String from = System.getenv("RESEND_FROM");
        return from == null || from.isBlank() ? "onboarding@resend.dev" : from;
    }

    private String json(Map<String, Object> data) {
        return "{" + data.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ":" + toJsonValue(entry.getValue()))
                .collect(Collectors.joining(",")) + "}";
    }

    private String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return quote(stringValue);
        }
        if (value instanceof List<?> list) {
            return "[" + list.stream().map(this::toJsonValue).collect(Collectors.joining(",")) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return quote(value.toString());
    }

    private String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
