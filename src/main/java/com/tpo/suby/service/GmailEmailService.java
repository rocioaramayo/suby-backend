package com.tpo.suby.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

@Service
@Slf4j
public class GmailEmailService {

    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    private static final URI GMAIL_SEND_ENDPOINT = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile TokenState tokenState;

    @PostConstruct
    void logConfiguration() {
        log.info("Gmail API configured: fromEmailPresent={}, clientIdPresent={}, refreshTokenPresent={}",
                hasText(defaultFromEmail()),
                hasText(clientId()),
                hasText(refreshToken()));
    }

    public void send(String to, String subject, String text) {
        String accessToken = accessToken();
        String fromEmail = defaultFromEmail();
        if (!hasText(fromEmail)) {
            throw new IllegalStateException("Configurá GMAIL_FROM_EMAIL (o MAIL_USERNAME) con tu cuenta Gmail.");
        }

        try {
            MimeMessage mimeMessage = createMimeMessage(fromEmail, defaultFromName(), to, subject, text);
            String raw = encodeMimeMessage(mimeMessage);
            String payload = "{\"raw\":\"" + raw + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(GMAIL_SEND_ENDPOINT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                log.warn("Gmail API error {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("No se pudo enviar el correo con Gmail API.");
            }
        } catch (Exception e) {
            throw new RuntimeException("No se pudo enviar el correo con Gmail API.", e);
        }
    }

    private synchronized String accessToken() {
        if (tokenState != null && tokenState.isValid()) {
            return tokenState.accessToken();
        }

        String clientId = clientId();
        String clientSecret = clientSecret();
        String refreshToken = refreshToken();

        if (!hasText(clientId) || !hasText(clientSecret) || !hasText(refreshToken)) {
            throw new IllegalStateException("Configurá GMAIL_CLIENT_ID, GMAIL_CLIENT_SECRET y GMAIL_REFRESH_TOKEN.");
        }

        String form = "client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret)
                + "&refresh_token=" + urlEncode(refreshToken)
                + "&grant_type=refresh_token";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(TOKEN_ENDPOINT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Google token refresh error {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("No se pudo renovar el token de Gmail.");
            }

            JsonNode json = objectMapper.readTree(response.body());
            String accessToken = json.path("access_token").asText(null);
            long expiresIn = json.path("expires_in").asLong(0L);
            if (!hasText(accessToken) || expiresIn <= 0) {
                throw new RuntimeException("Respuesta inválida al renovar el token de Gmail.");
            }

            tokenState = new TokenState(accessToken, Instant.now().plusSeconds(Math.max(expiresIn - 60, 60)));
            return tokenState.accessToken();
        } catch (IOException e) {
            throw new RuntimeException("No se pudo renovar el token de Gmail.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("No se pudo renovar el token de Gmail.", e);
        }
    }

    private MimeMessage createMimeMessage(String fromEmail, String fromName, String to, String subject, String text)
            throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);

        InternetAddress fromAddress = new InternetAddress(fromEmail);
        if (hasText(fromName)) {
            fromAddress.setPersonal(fromName, StandardCharsets.UTF_8.name());
        }

        message.setFrom(fromAddress);
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(to, false));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(text, StandardCharsets.UTF_8.name());
        message.saveChanges();
        return message;
    }

    private String encodeMimeMessage(MimeMessage message) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        message.writeTo(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
    }

    private String clientId() {
        return System.getenv("GMAIL_CLIENT_ID");
    }

    private String clientSecret() {
        return System.getenv("GMAIL_CLIENT_SECRET");
    }

    private String refreshToken() {
        return System.getenv("GMAIL_REFRESH_TOKEN");
    }

    private String defaultFromEmail() {
        return firstNonBlank(System.getenv("GMAIL_FROM_EMAIL"), System.getenv("MAIL_USERNAME"));
    }

    private String defaultFromName() {
        return firstNonBlank(System.getenv("GMAIL_FROM_NAME"), "Suby");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record TokenState(String accessToken, Instant expiresAt) {
        boolean isValid() {
            return accessToken != null && !accessToken.isBlank()
                    && expiresAt != null
                    && Instant.now().isBefore(expiresAt);
        }
    }
}
