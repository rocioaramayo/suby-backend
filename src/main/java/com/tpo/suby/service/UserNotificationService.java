package com.tpo.suby.service;

import com.tpo.suby.dto.response.user.UserNotificationDetailResponse;
import com.tpo.suby.dto.response.user.UserNotificationItemResponse;
import com.tpo.suby.dto.response.user.UserNotificationReadResponse;
import com.tpo.suby.dto.response.user.UserNotificationsResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private static final int PAYMENT_NOTIFICATION_OFFSET = 1_000_000;
    private static final int FINE_NOTIFICATION_OFFSET = 2_000_000;

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;

    public UserNotificationsResponse getNotifications(Integer userId, Boolean unreadOnly) {
        validateOwner(userId);

        List<NotificationRow> notifications = allNotifications(userId);
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

    public UserNotificationDetailResponse getNotificationDetail(Integer userId, Integer notificationId) {
        validateOwner(userId);

        NotificationRow notification = findNotification(userId, notificationId);
        Map<String, String> data = notification.source() == NotificationSource.PRIVATE_MESSAGE
                ? enrichPrivateMessageData(userId, privateMessageData(notification.rawId()))
                : Map.of();

        return UserNotificationDetailResponse.builder()
                .id(notification.id())
                .type(notification.type())
                .title(notification.title())
                .body(notification.body())
                .read(notification.read())
                .createdAt(notification.createdAt()
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toString())
                .data(data)
                .build();
    }

    public UserNotificationReadResponse markAsRead(Integer userId, Integer notificationId) {
        validateOwner(userId);

        NotificationRow notification = findNotification(userId, notificationId);
        if (notification.source() == NotificationSource.PRIVATE_MESSAGE && !notification.read()) {
            int updated = jdbcTemplate.update("""
                    UPDATE mensajes_privados
                    SET leido = 'si',
                        fechaLeido = GETDATE()
                    WHERE identificador = ?
                      AND destinatario = ?
                    """, notification.rawId(), userId);

            if (updated == 0) {
                throw new NotFoundException("Mensaje no encontrado.");
            }
        }

        return UserNotificationReadResponse.builder()
                .id(notification.id())
                .read(true)
                .build();
    }

    private List<NotificationRow> allNotifications(Integer userId) {
        List<NotificationRow> notifications = new ArrayList<>();
        notifications.addAll(privateMessages(userId));
        notifications.addAll(paymentNotifications(userId));
        notifications.addAll(fineNotifications(userId));
        return notifications;
    }

    private NotificationRow findNotification(Integer userId, Integer notificationId) {
        return allNotifications(userId).stream()
                .filter(notification -> notification.id().equals(notificationId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Mensaje no encontrado."));
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
                rs.getTimestamp("created_at").toLocalDateTime(),
                NotificationSource.PRIVATE_MESSAGE,
                rs.getInt("id")
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
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    NotificationSource.PAYMENT,
                    rs.getInt("id")
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
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    NotificationSource.FINE,
                    rs.getInt("id")
            );
        }, userId);
    }

    private Map<String, String> privateMessageData(Integer messageId) {
        List<Map.Entry<String, String>> rows = jdbcTemplate.query("""
                SELECT clave, valor
                FROM mensajes_datos
                WHERE mensaje = ?
                ORDER BY identificador ASC
                """, (rs, rowNum) -> Map.entry(
                rs.getString("clave"),
                rs.getString("valor")
        ), messageId);

        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows) {
            data.put(row.getKey(), row.getValue());
        }
        return data;
    }

    private Map<String, String> enrichPrivateMessageData(Integer userId, Map<String, String> original) {
        Map<String, String> data = new LinkedHashMap<>(original);

        Optional<Integer> productId = parseInt(data.get("product_id"));
        if (productId.isPresent()) {
            Integer firstPhotoId = firstProductPhotoId(userId, productId.get());
            if (firstPhotoId != null) {
                data.putIfAbsent(
                        "image_url",
                        "/api/v1/users/%d/products/%d/photos/%d".formatted(userId, productId.get(), firstPhotoId)
                );
            }

            ProductInfo productInfo = loadProductInfo(productId.get());
            if (productInfo != null) {
                data.putIfAbsent("product_name", productInfo.name());
                data.putIfAbsent("product_code", "PROD-" + productId.get());
            }
        }

        return data;
    }

    private Integer firstProductPhotoId(Integer userId, Integer productId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 f.identificador
                    FROM productos p
                    JOIN fotos f ON f.producto = p.identificador
                    WHERE p.identificador = ?
                      AND p.duenio = ?
                    ORDER BY f.identificador ASC
                    """, Integer.class, productId, userId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private ProductInfo loadProductInfo(Integer productId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS name
                    FROM productos p
                    LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                    WHERE p.identificador = ?
                    """, (rs, rowNum) -> new ProductInfo(rs.getString("name")), productId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private Optional<Integer> parseInt(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
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
            java.time.LocalDateTime createdAt,
            NotificationSource source,
            Integer rawId
    ) {
    }

    private enum NotificationSource {
        PRIVATE_MESSAGE,
        PAYMENT,
        FINE
    }

    private record ProductInfo(String name) {
    }
}
