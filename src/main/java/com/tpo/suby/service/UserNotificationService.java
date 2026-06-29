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
    private static final int DIFFERENCE_NOTIFICATION_OFFSET = 3_000_000;
    private static final int PAYMENT_REMINDER_DELAY_MINUTES = 30;

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;
    private final ProductDepositService productDepositService;

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
        Map<String, String> data = switch (notification.source()) {
            case PRIVATE_MESSAGE -> enrichPrivateMessageData(userId, privateMessageData(notification.rawId()));
            case PAYMENT -> paymentNotificationData(userId, notification.rawId());
            case FINE -> fineNotificationData(userId, notification.rawId());
            case DIFFERENCE -> differenceNotificationData(userId, notification.rawId());
        };

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
        notifications.addAll(differenceNotifications(userId));
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
                    CASE
                        WHEN mp.tipo = 'aviso_general' AND fk.valor = 'proposal_price'    THEN 'propuesta_precio'
                        WHEN mp.tipo = 'aviso_general' AND fk.valor = 'diferencia_saldo'  THEN 'diferencia_saldo'
                        ELSE mp.tipo
                    END AS type,
                    mp.asunto AS title,
                    mp.cuerpo AS body,
                    CASE WHEN mp.leido = 'si' THEN 1 ELSE 0 END AS is_read,
                    mp.enviadoEn AS created_at
                FROM mensajes_privados mp
                LEFT JOIN mensajes_datos fk ON fk.mensaje = mp.identificador AND fk.clave = 'flow_kind'
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
                  AND np.fechaEnvio <= DATEADD(MINUTE, -?, GETDATE())
                  AND NOT EXISTS (
                        SELECT 1
                        FROM pagos pg
                        WHERE pg.registroSubasta = np.registro
                          AND pg.cliente = np.cliente
                          AND pg.estado IN ('pendiente', 'procesando', 'confirmado')
                  )
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
        }, userId, PAYMENT_REMINDER_DELAY_MINUTES);
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

    private List<NotificationRow> differenceNotifications(Integer userId) {
        return jdbcTemplate.query("""
                SELECT
                    d.identificador       AS id,
                    d.estado              AS diff_status,
                    d.importeDiferencia   AS difference_amount,
                    d.fechaGeneracion     AS created_at
                FROM diferencias_saldo d
                WHERE d.cliente = ?
                  AND d.estado IN ('pendiente', 'vencida')
                """, (rs, rowNum) -> {
            String diffStatus = normalize(rs.getString("diff_status"));
            String type = "vencida".equals(diffStatus) ? "diferencia_vencida" : "diferencia_pendiente";

            String title = "vencida".equals(diffStatus)
                    ? "Tu diferencia de saldo está vencida"
                    : "Tenés una diferencia de saldo pendiente";

            String body = "vencida".equals(diffStatus)
                    ? "Tu diferencia por %s está vencida. Regularizá tu situación para rehabilitar tu cuenta."
                            .formatted(formatMoney(rs.getBigDecimal("difference_amount")))
                    : "Tenés una diferencia de saldo pendiente por %s. Debés abonarla dentro de las 72 hs."
                            .formatted(formatMoney(rs.getBigDecimal("difference_amount")));

            return new NotificationRow(
                    DIFFERENCE_NOTIFICATION_OFFSET + rs.getInt("id"),
                    type,
                    title,
                    body,
                    false,
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    NotificationSource.DIFFERENCE,
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

    private Map<String, String> paymentNotificationData(Integer userId, Integer notificationId) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    rs.producto AS product_id,
                    ic.identificador AS item_id,
                    COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS product_name,
                    CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                    COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                    np.importePujado AS winning_bid,
                    np.comision AS commission_amount,
                    np.costoEnvio AS shipping_amount,
                    np.importeTotal AS total_to_pay,
                    COALESCE(se.moneda, 'ARS') AS currency,
                    (
                        SELECT TOP 1 f.identificador
                        FROM fotos f
                        WHERE f.producto = p.identificador
                        ORDER BY f.identificador ASC
                    ) AS first_photo_id
                FROM notificacionesPago np
                JOIN registroDeSubasta rs ON rs.identificador = np.registro
                JOIN productos p ON p.identificador = rs.producto
                LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                LEFT JOIN subastas s ON s.identificador = rs.subasta
                LEFT JOIN subastas_ext se ON se.identificador = s.identificador
                LEFT JOIN catalogos c ON c.subasta = s.identificador
                LEFT JOIN itemsCatalogo ic ON ic.producto = p.identificador
                WHERE np.identificador = ?
                  AND np.cliente = ?
                """, (rs, rowNum) -> {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("headline", "Informacion de pago - Subasta Ganada");
            data.put("item_id", String.valueOf(rs.getInt("item_id")));
            data.put("product_id", String.valueOf(rs.getInt("product_id")));
            data.put("lot_code", rs.getString("lot_code"));
            data.put("item_title", rs.getString("product_name"));
            data.put("auction_name", rs.getString("auction_name"));
            data.put("winning_bid", formatMoney(rs.getBigDecimal("winning_bid")));
            data.put("commission_amount", formatMoney(rs.getBigDecimal("commission_amount")));
            data.put("shipping_amount", formatMoney(rs.getBigDecimal("shipping_amount")));
            data.put("total_to_pay", formatMoney(rs.getBigDecimal("total_to_pay")));
            data.put("currency", rs.getString("currency"));
            Integer photoId = rs.getInt("first_photo_id");
            if (!rs.wasNull()) {
                data.put("image_url", "/api/v1/users/%d/products/%d/photos/%d".formatted(userId, rs.getInt("product_id"), photoId));
            }
            data.put("cta_label", "Ir a pagar");
            data.put("cta_target", "/won-items/%s/payment".formatted(rs.getInt("item_id")));
            return data;
        }, notificationId, userId);
    }

    private Map<String, String> fineNotificationData(Integer userId, Integer fineId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("fine_id", String.valueOf(fineId));
        data.put("user_id", String.valueOf(userId));
        return data;
    }

    private Map<String, String> differenceNotificationData(Integer userId, Integer differenceId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("difference_id", String.valueOf(differenceId));
        data.put("user_id", String.valueOf(userId));
        data.put("cta_label", "Pagar diferencia");
        data.put("cta_target", "/differences");
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

            if ("submission_received".equalsIgnoreCase(data.get("flow_kind"))) {
                productDepositService.applySubmissionDepositData(data, productId.get());
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
        FINE,
        DIFFERENCE
    }

    private record ProductInfo(String name) {
    }
}
