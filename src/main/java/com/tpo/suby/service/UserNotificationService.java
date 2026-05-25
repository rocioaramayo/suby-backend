package com.tpo.suby.service;

import com.tpo.suby.dto.response.user.UserNotificationItemResponse;
import com.tpo.suby.dto.response.user.UserNotificationsResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private static final int PAYMENT_NOTIFICATION_OFFSET = 1_000_000;
    private static final int FINE_NOTIFICATION_OFFSET = 2_000_000;

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;

    public UserNotificationsResponse getNotifications(Integer userId, Boolean unreadOnly) {
        validateOwner(userId);

        List<NotificationRow> notifications = new ArrayList<>();
        notifications.addAll(privateMessages(userId));
        notifications.addAll(paymentNotifications(userId));
        notifications.addAll(fineNotifications(userId));

        notifications.sort(Comparator.comparing(NotificationRow::createdAt).reversed());

        long unreadCount = notifications.stream()
                .filter(notification -> !notification.read())
                .count();

        List<UserNotificationItemResponse> visibleNotifications = notifications.stream()
                .filter(notification -> !Boolean.TRUE.equals(unreadOnly) || !notification.read())
                .map(notification -> UserNotificationItemResponse.builder()
                        .id(notification.id())
                        .type(notification.type())
                        .title(notification.title())
                        .body(notification.body())
                        .read(notification.read())
                        .createdAt(notification.createdAt()
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toString())
                        .build())
                .toList();

        return UserNotificationsResponse.builder()
                .notifications(visibleNotifications)
                .unreadCount((int) unreadCount)
                .build();
    }

    private List<NotificationRow> privateMessages(Integer userId) {
        return jdbcTemplate.query("""
                SELECT
                    mp.identificador AS id,
                    mp.tipo AS type,
                    mp.asunto AS title,
                    mp.cuerpo AS body,
                    CASE WHEN mp.leido = 'si' THEN 1 ELSE 0 END AS is_read,
                    mp.enviadoEn AS created_at
                FROM mensajes_privados mp
                WHERE mp.destinatario = ?
                """, (rs, rowNum) -> new NotificationRow(
                rs.getInt("id"),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getInt("is_read") == 1,
                rs.getTimestamp("created_at").toLocalDateTime()
        ), userId);
    }

    private List<NotificationRow> paymentNotifications(Integer userId) {
        return jdbcTemplate.query("""
                SELECT
                    np.identificador AS id,
                    COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS item_title,
                    COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                    np.importeTotal AS total_amount,
                    np.leido AS read_flag,
                    np.fechaEnvio AS created_at
                FROM notificacionesPago np
                JOIN registroDeSubasta rs ON rs.identificador = np.registro
                LEFT JOIN productos p ON p.identificador = rs.producto
                LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                LEFT JOIN subastas s ON s.identificador = rs.subasta
                LEFT JOIN catalogos c ON c.subasta = s.identificador
                WHERE np.cliente = ?
                """, (rs, rowNum) -> {
            String itemTitle = rs.getString("item_title");
            String auctionName = rs.getString("auction_name");
            BigDecimal totalAmount = rs.getBigDecimal("total_amount");

            return new NotificationRow(
                    PAYMENT_NOTIFICATION_OFFSET + rs.getInt("id"),
                    "pago_pendiente",
                    "Recordatorio de pago",
                    "Tenés un pago pendiente por %s en %s."
                            .formatted(formatMoney(totalAmount), defaultText(auctionName, itemTitle)),
                    "si".equalsIgnoreCase(rs.getString("read_flag")),
                    rs.getTimestamp("created_at").toLocalDateTime()
            );
        }, userId);
    }

    private List<NotificationRow> fineNotifications(Integer userId) {
        return jdbcTemplate.query("""
                SELECT
                    m.identificador AS id,
                    m.estado AS fine_status,
                    m.importeMulta AS fine_amount,
                    m.fechaGeneracion AS created_at
                FROM multas m
                WHERE m.cliente = ?
                  AND m.estado IN ('pendiente', 'vencida', 'judicial')
                """, (rs, rowNum) -> {
            String fineStatus = normalize(rs.getString("fine_status"));
            String type = switch (fineStatus) {
                case "judicial" -> "alerta_judicial";
                case "vencida" -> "multa_vencida";
                default -> "multa_generada";
            };

            String title = switch (type) {
                case "alerta_judicial" -> "Tu cuenta tiene una alerta judicial";
                case "multa_vencida" -> "Tu multa está vencida";
                default -> "Tenés una multa pendiente";
            };

            String body = switch (type) {
                case "alerta_judicial" -> "Tu multa fue derivada a gestión judicial. Regularizá tu situación cuanto antes.";
                case "multa_vencida" -> "Tu multa por %s está vencida. Necesitás abonarla para evitar más restricciones."
                        .formatted(formatMoney(rs.getBigDecimal("fine_amount")));
                default -> "Tenés una multa pendiente por %s."
                        .formatted(formatMoney(rs.getBigDecimal("fine_amount")));
            };

            return new NotificationRow(
                    FINE_NOTIFICATION_OFFSET + rs.getInt("id"),
                    type,
                    title,
                    body,
                    false,
                    rs.getTimestamp("created_at").toLocalDateTime()
            );
        }, userId);
    }

    private void validateOwner(Integer userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("No autorizado.");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("No autorizado.");
        }

        UsuarioApp user = usuarioAppRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("No autorizado."));

        if (!user.getIdentificador().equals(userId)) {
            throw new UnauthorizedException("No autorizado.");
        }
    }

    private String formatMoney(BigDecimal amount) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        return "$ " + safeAmount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String defaultText(String auctionName, String itemTitle) {
        if (auctionName != null && !auctionName.isBlank()) {
            return auctionName;
        }
        return itemTitle == null || itemTitle.isBlank() ? "tu compra" : itemTitle;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record NotificationRow(
            Integer id,
            String type,
            String title,
            String body,
            boolean read,
            java.time.LocalDateTime createdAt
    ) {
    }
}
