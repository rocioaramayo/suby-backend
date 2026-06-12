package com.tpo.suby.service;

import com.tpo.suby.dto.request.user.WonItemPaymentRequest;
import com.tpo.suby.dto.response.payment.PaymentMethodItemResponse;
import com.tpo.suby.dto.response.user.UserBidHistoryItemResponse;
import com.tpo.suby.dto.response.user.UserBidHistoryResponse;
import com.tpo.suby.dto.response.user.WonBidAuctionResponse;
import com.tpo.suby.dto.response.user.WonBidDetailResponse;
import com.tpo.suby.dto.response.user.WonBidItemResponse;
import com.tpo.suby.dto.response.user.WonBidResultResponse;
import com.tpo.suby.dto.response.user.WonBidTimelineItemResponse;
import com.tpo.suby.dto.response.user.WonItemPaymentDetailResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.InsufficientPaymentMethodBalanceException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.exception.WonBidDetailForbiddenException;
import com.tpo.suby.exception.WonBidDetailNotFoundException;
import com.tpo.suby.exception.WonItemAlreadyPaidException;
import com.tpo.suby.exception.WonItemPaymentNotFoundException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserBidService {

    private static final BigDecimal ESTIMATED_SHIPPING_AMOUNT = new BigDecimal("150.00");

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;
    private final PrivateMessageService privateMessageService;
    private final UserCategoryService userCategoryService;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String mailFrom;

    public UserBidHistoryResponse getBidHistory(Integer userId) {
        validateOwner(userId);

        List<UserBidHistoryItemResponse> bids = jdbcTemplate.query("""
                SELECT
                    ic.identificador AS item_id,
                    s.identificador AS auction_id,
                    pu.identificador AS bid_id,
                    COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                    CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                    p.descripcionCompleta AS item_title,
                    pu.importe AS amount,
                    COALESCE(pu.ganador, 'no') AS winner,
                    s.fecha AS date
                FROM pujos pu
                JOIN asistentes a ON a.identificador = pu.asistente
                JOIN itemsCatalogo ic ON ic.identificador = pu.item
                JOIN productos p ON p.identificador = ic.producto
                JOIN catalogos c ON c.identificador = ic.catalogo
                JOIN subastas s ON s.identificador = c.subasta
                WHERE a.cliente = ?
                ORDER BY s.fecha DESC, pu.identificador DESC
                """, (rs, rowNum) -> UserBidHistoryItemResponse.builder()
                .itemId(rs.getInt("item_id"))
                .auctionId(rs.getInt("auction_id"))
                .bidId(rs.getInt("bid_id"))
                .auctionName(rs.getString("auction_name"))
                .lotCode(rs.getString("lot_code"))
                .itemTitle(rs.getString("item_title"))
                .amount(rs.getBigDecimal("amount"))
                .winner(rs.getString("winner"))
                .date(toLocalDate(rs.getDate("date")))
                .build(), userId);

        return UserBidHistoryResponse.builder()
                .bids(bids)
                .total(bids.size())
                .build();
    }

    public WonBidDetailResponse getWonBidDetail(Integer userId, Integer itemId) {
        validateOwner(userId);

        if (itemId == null || itemId <= 0) {
            throw new WonBidDetailNotFoundException("Lote no encontrado en tu historial.");
        }

        WonBidCore wonBid = wonBidCore(userId, itemId);
        if (!"si".equalsIgnoreCase(wonBid.winner())) {
            boolean hasAnyBid = hasAnyBidForItem(userId, itemId);
            if (hasAnyBid) {
                throw new WonBidDetailForbiddenException("Este lote no fue adjudicado a tu cuenta.");
            }
            throw new WonBidDetailNotFoundException("Lote no encontrado en tu historial.");
        }

        List<WonBidTimelineItemResponse> timeline = bidTimeline(userId, itemId, wonBid.auctionDate(), wonBid.auctionHour());

        BigDecimal commissionPct = commissionPercentage(wonBid.commission(), wonBid.basePrice());
        BigDecimal commissionAmount = wonBid.winningBid()
                .multiply(commissionPct)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        return WonBidDetailResponse.builder()
                .item(WonBidItemResponse.builder()
                        .itemId(wonBid.itemId())
                        .lotCode(wonBid.lotCode())
                        .title(wonBid.title())
                        .description(wonBid.description())
                        .ownerAtSale(wonBid.ownerAtSale())
                        .photos(List.of())
                        .build())
                .auction(WonBidAuctionResponse.builder()
                        .id(wonBid.auctionId())
                        .name(wonBid.auctionName())
                        .date(wonBid.auctionDate())
                        .location(wonBid.location())
                        .auctioneer(wonBid.auctioneer())
                        .build())
                .result(WonBidResultResponse.builder()
                        .winningBid(wonBid.winningBid())
                        .subyCommissionPct(commissionPct)
                        .subyCommissionAmount(commissionAmount)
                        .totalPaid(wonBid.winningBid().add(commissionAmount))
                        .build())
                .bidTimeline(timeline)
                .build();
    }

    public WonItemPaymentDetailResponse getWonItemPaymentDetail(Integer userId, Integer itemId) {
        validateOwner(userId);

        if (itemId == null || itemId <= 0) {
            throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
        }

        WonBidCore wonBid;
        try {
            wonBid = wonBidCore(userId, itemId);
        } catch (WonBidDetailNotFoundException ex) {
            throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
        }
        if (!"si".equalsIgnoreCase(wonBid.winner())) {
            throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
        }

        BigDecimal commissionPct = commissionPercentage(wonBid.commission(), wonBid.basePrice());
        BigDecimal commissionAmount = wonBid.winningBid()
                .multiply(commissionPct)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal shippingAmount = ESTIMATED_SHIPPING_AMOUNT;

        return WonItemPaymentDetailResponse.builder()
                .itemId(wonBid.itemId())
                .lotCode(wonBid.lotCode())
                .title(wonBid.title())
                .auctionName(wonBid.auctionName())
                .auctioneer(wonBid.auctioneer())
                .currency(wonBid.auctionCurrency())
                .winningBid(wonBid.winningBid())
                .commission(commissionAmount)
                .shippingAmount(shippingAmount)
                .pickupAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .totalToPay(wonBid.winningBid().add(commissionAmount).add(shippingAmount))
                .estimatedPaymentDate(wonBid.auctionDate().plusDays(6))
                .pickupAvailable(true)
                .shippingAddress(ownerAddress(userId))
                .paymentMethods(paymentMethods(userId))
                .build();
    }

    @Transactional
    public String confirmWonItemPayment(Integer userId, Integer itemId, WonItemPaymentRequest request) {
        validateOwner(userId);

        if (itemId == null || itemId <= 0 || request == null || request.getPaymentMethodId() == null) {
            throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
        }

        WonBidCore wonBid;
        try {
            wonBid = wonBidCore(userId, itemId);
        } catch (WonBidDetailNotFoundException ex) {
            throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
        }
        if (!"si".equalsIgnoreCase(wonBid.winner())) {
            throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
        }

        BigDecimal commissionPct = commissionPercentage(wonBid.commission(), wonBid.basePrice());
        BigDecimal commissionAmount = wonBid.winningBid()
                .multiply(commissionPct)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        boolean retiroPresencial = Boolean.TRUE.equals(request.getRetiroPresencial());
        BigDecimal shippingAmount = retiroPresencial
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : ESTIMATED_SHIPPING_AMOUNT;
        String shippingAddress = retiroPresencial ? null : ownerAddress(userId);
        BigDecimal totalToPay = wonBid.winningBid().add(commissionAmount).add(shippingAmount);

        PaymentMethodState paymentMethod = paymentMethodState(userId, request.getPaymentMethodId(), wonBid.auctionId());
        if (!paymentMethod.canCover(totalToPay, wonBid.auctionCurrency())) {
            throw new InsufficientPaymentMethodBalanceException("Saldo insuficiente en el medio de pago seleccionado.");
        }

        Integer registroId = ensureRegistroSubasta(userId, wonBid, commissionAmount);
        ensureNotPaid(registroId, userId);
        insertPago(userId, registroId, request.getPaymentMethodId(), totalToPay, wonBid.auctionCurrency(), retiroPresencial, shippingAddress);
        reservePaymentMethodAmount(paymentMethod, totalToPay);
        privateMessageService.createPrivateMessage(
                userId,
                "pago_confirmado",
                "Pago confirmado",
                "Se confirmo el pago de %s por %s en %s."
                        .formatted(formatMoney(totalToPay), defaultText(wonBid.title()), defaultText(wonBid.auctionName())),
                paymentConfirmedMessageData(userId, wonBid, commissionPct, commissionAmount, shippingAmount, totalToPay, retiroPresencial, shippingAddress)
        );
        sendPaymentConfirmationEmail(userId, wonBid, commissionAmount, shippingAmount, totalToPay, retiroPresencial, shippingAddress);
        userCategoryService.refreshCategory(userId);

        return "Pago confirmado. Recibiras la confirmacion por email.";
    }

    private WonBidCore wonBidCore(Integer userId, Integer itemId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        ic.identificador AS item_id,
                        CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                        p.descripcionCatalogo AS title,
                        p.descripcionCompleta AS description,
                        owner.nombre AS owner_at_sale,
                        d.identificador AS owner_id,
                        s.identificador AS auction_id,
                        COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                        s.fecha AS auction_date,
                        s.hora AS auction_hour,
                        s.ubicacion AS auction_location,
                        auctioneer_person.nombre AS auctioneer,
                        p.identificador AS product_id,
                        COALESCE(se.moneda, 'ARS') AS currency,
                        winning_bid.importe AS winning_bid,
                        winning_bid.ganador AS winner,
                        ic.comision AS commission,
                        ic.precioBase AS base_price
                    FROM pujos winning_bid
                    JOIN asistentes my_attendee ON my_attendee.identificador = winning_bid.asistente
                    JOIN itemsCatalogo ic ON ic.identificador = winning_bid.item
                    JOIN productos p ON p.identificador = ic.producto
                    JOIN catalogos c ON c.identificador = ic.catalogo
                    JOIN subastas s ON s.identificador = c.subasta
                    LEFT JOIN duenios d ON d.identificador = p.duenio
                    LEFT JOIN personas owner ON owner.identificador = d.identificador
                    LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                    LEFT JOIN personas auctioneer_person ON auctioneer_person.identificador = sub.identificador
                    LEFT JOIN subastas_ext se ON se.identificador = s.identificador
                    WHERE my_attendee.cliente = ?
                      AND ic.identificador = ?
                    ORDER BY
                        CASE WHEN winning_bid.ganador = 'si' THEN 0 ELSE 1 END,
                        winning_bid.importe DESC,
                        winning_bid.identificador DESC
                    """, (rs, rowNum) -> new WonBidCore(
                    rs.getInt("item_id"),
                    rs.getString("lot_code"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("owner_at_sale"),
                    rs.getInt("owner_id"),
                    rs.getInt("auction_id"),
                    rs.getString("auction_name"),
                    toLocalDate(rs.getDate("auction_date")),
                    rs.getTime("auction_hour").toLocalTime(),
                    rs.getString("auction_location"),
                    rs.getString("auctioneer"),
                    rs.getInt("product_id"),
                    rs.getString("currency"),
                    rs.getBigDecimal("winning_bid"),
                    rs.getString("winner"),
                    rs.getBigDecimal("commission"),
                    rs.getBigDecimal("base_price")
            ), userId, itemId);
        } catch (EmptyResultDataAccessException ex) {
            throw new WonBidDetailNotFoundException("Lote no encontrado en tu historial.");
        }
    }

    private boolean hasAnyBidForItem(Integer userId, Integer itemId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pujos pu
                JOIN asistentes a ON a.identificador = pu.asistente
                WHERE a.cliente = ?
                  AND pu.item = ?
                """, Integer.class, userId, itemId);
        return count != null && count > 0;
    }

    private List<WonBidTimelineItemResponse> bidTimeline(Integer userId, Integer itemId, LocalDate auctionDate, java.time.LocalTime auctionHour) {
        List<TimelineRow> rows = jdbcTemplate.query("""
                SELECT
                    pu.identificador AS bid_id,
                    a.numeroPostor AS bidder_number,
                    a.cliente AS bidder_client_id,
                    pu.importe AS amount
                FROM pujos pu
                JOIN asistentes a ON a.identificador = pu.asistente
                WHERE pu.item = ?
                ORDER BY pu.identificador ASC
                """, (rs, rowNum) -> new TimelineRow(
                rs.getInt("bid_id"),
                rs.getInt("bidder_number"),
                rs.getInt("bidder_client_id"),
                rs.getBigDecimal("amount")
        ), itemId);

        LocalDateTime baseTime = LocalDateTime.of(auctionDate, auctionHour);
        return java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> {
                    TimelineRow row = rows.get(index);
                    String bidderLabel = "Postor %02d".formatted(row.bidderNumber());
                    if (row.bidderClientId().equals(userId)) {
                        bidderLabel += " (Tu)";
                    }
                    return WonBidTimelineItemResponse.builder()
                            .bidNumber(index + 1)
                            .bidderLabel(bidderLabel)
                            .amount(row.amount())
                            .timestamp(baseTime.plusSeconds((long) (index + 1) * 36)
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toString())
                            .build();
                })
                .toList();
    }

    private List<PaymentMethodItemResponse> paymentMethods(Integer userId) {
        return jdbcTemplate.query("""
                SELECT
                    mdp.identificador AS id,
                    mdp.tipo AS db_type,
                    mdp.montoDisponible AS available_balance,
                    mdp.montoUsado AS used_balance,
                    tc.numeroEnmascarado,
                    tc.redTarjeta,
                    cb.banco AS cuenta_banco,
                    cb.numeroCuenta,
                    cc.banco AS cheque_banco
                FROM mediosDePago mdp
                LEFT JOIN tarjetasCredito tc ON tc.identificador = mdp.identificador
                LEFT JOIN cuentasBancarias cb ON cb.identificador = mdp.identificador
                LEFT JOIN chequesCertificados cc ON cc.identificador = mdp.identificador
                WHERE mdp.cliente = ?
                ORDER BY mdp.identificador ASC
                """, (rs, rowNum) -> {
            String dbType = rs.getString("db_type");
            BigDecimal available = rs.getBigDecimal("available_balance");
            BigDecimal used = rs.getBigDecimal("used_balance");
            if (available == null) {
                available = BigDecimal.ZERO;
            }
            if (used == null) {
                used = BigDecimal.ZERO;
            }

            return PaymentMethodItemResponse.builder()
                    .id(rs.getInt("id"))
                    .type(toApiType(dbType))
                    .label(buildLabel(
                            dbType,
                            rs.getString("redTarjeta"),
                            rs.getString("numeroEnmascarado"),
                            rs.getString("cuenta_banco"),
                            rs.getString("numeroCuenta"),
                            rs.getString("cheque_banco")
                    ))
                    .availableBalance(available.subtract(used))
                    .build();
        }, userId);
    }

    private PaymentMethodState paymentMethodState(Integer userId, Integer paymentMethodId, Integer auctionId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        mdp.identificador AS id,
                        mdp.tipo AS payment_type,
                        mdp.estado AS payment_status,
                        mdp.moneda AS payment_currency,
                        COALESCE(mdp.montoDisponible, 0) AS available_balance,
                        COALESCE(mdp.montoUsado, 0) AS used_balance,
                        COALESCE(tc.esInternacional, 'no') AS international_card,
                        cc.subasta AS check_auction_id
                    FROM mediosDePago mdp
                    LEFT JOIN tarjetasCredito tc ON tc.identificador = mdp.identificador
                    LEFT JOIN chequesCertificados cc ON cc.identificador = mdp.identificador
                    WHERE mdp.identificador = ?
                      AND mdp.cliente = ?
                    """, (rs, rowNum) -> new PaymentMethodState(
                    rs.getInt("id"),
                    rs.getString("payment_type"),
                    rs.getString("payment_status"),
                    rs.getString("payment_currency"),
                    rs.getBigDecimal("available_balance").subtract(rs.getBigDecimal("used_balance")),
                    "si".equalsIgnoreCase(rs.getString("international_card")),
                    nullableInt(rs, "check_auction_id"),
                    auctionId
            ), paymentMethodId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
        }
    }

    private Integer ensureRegistroSubasta(Integer userId, WonBidCore wonBid, BigDecimal commissionAmount) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 identificador
                    FROM registroDeSubasta
                    WHERE subasta = ?
                      AND producto = ?
                      AND cliente = ?
                    ORDER BY identificador DESC
                    """, Integer.class, wonBid.auctionId(), wonBid.productId(), userId);
        } catch (EmptyResultDataAccessException ex) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO registroDeSubasta (
                            subasta, duenio, producto, cliente, importe, comision
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, wonBid.auctionId());
                ps.setInt(2, wonBid.ownerId());
                ps.setInt(3, wonBid.productId());
                ps.setInt(4, userId);
                ps.setBigDecimal(5, wonBid.winningBid());
                ps.setBigDecimal(6, commissionAmount);
                return ps;
            }, keyHolder);

            Number key = keyHolder.getKey();
            if (key == null) {
                throw new WonItemPaymentNotFoundException("Articulo no encontrado o no adjudicado a tu cuenta.");
            }
            return key.intValue();
        }
    }

    private void ensureNotPaid(Integer registroId, Integer userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pagos
                WHERE registroSubasta = ?
                  AND cliente = ?
                  AND estado IN ('pendiente', 'procesando', 'confirmado')
                """, Integer.class, registroId, userId);

        if (count != null && count > 0) {
            throw new WonItemAlreadyPaidException("Este articulo ya fue pagado.");
        }
    }

    private void insertPago(
            Integer userId,
            Integer registroId,
            Integer paymentMethodId,
            BigDecimal totalToPay,
            String currency,
            boolean retiroPresencial,
            String shippingAddress
    ) {
        jdbcTemplate.update("""
                INSERT INTO pagos (
                    cliente, registroSubasta, medioPago, importe, moneda,
                    estado, fechaConfirmacion, retiroPresencial, referenciaExterna
                )
                VALUES (?, ?, ?, ?, ?, ?, GETDATE(), ?, ?)
                """, userId, registroId, paymentMethodId, totalToPay, currency, "confirmado", retiroPresencial ? "si" : "no",
                buildPaymentReference(registroId, paymentMethodId, retiroPresencial, shippingAddress));
    }

    private void reservePaymentMethodAmount(PaymentMethodState paymentMethod, BigDecimal totalToPay) {
        if ("tarjeta_credito".equals(paymentMethod.type())) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE mediosDePago
                SET montoUsado = COALESCE(montoUsado, 0) + ?
                WHERE identificador = ?
                """, totalToPay, paymentMethod.id());
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

    private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private BigDecimal commissionPercentage(BigDecimal commission, BigDecimal basePrice) {
        if (commission == null || commission.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (commission.compareTo(new BigDecimal("100")) <= 0) {
            return commission.setScale(2, RoundingMode.HALF_UP);
        }
        return commission
                .multiply(new BigDecimal("100"))
                .divide(basePrice, 2, RoundingMode.HALF_UP);
    }

    private String toApiType(String dbType) {
        return switch (dbType) {
            case "tarjeta_credito" -> "tarjeta";
            case "cheque_certificado" -> "cheque";
            default -> dbType;
        };
    }

    private String buildLabel(String dbType, String cardBrand, String maskedCard, String bankName, String accountNumber, String checkBank) {
        return switch (dbType) {
            case "tarjeta_credito" -> cardLabel(cardBrand, maskedCard);
            case "cuenta_bancaria" -> "%s - Cta. %s".formatted(bankName, maskLast4(accountNumber));
            case "cheque_certificado" -> "Cheque certificado - %s".formatted(checkBank == null ? "Pendiente" : checkBank);
            default -> "Medio de pago";
        };
    }

    private String cardLabel(String brand, String masked) {
        return "%s %s".formatted(capitalize(brand), masked);
    }

    private String maskLast4(String value) {
        if (value == null || value.length() < 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
    }

    private String formatMoney(BigDecimal amount) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        return "$ " + safeAmount.setScale(2, RoundingMode.HALF_UP);
    }

    private String formatMoney(BigDecimal amount, String currency) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        String symbol = "USD".equalsIgnoreCase(currency) ? "US$ " : "$ ";
        return symbol + safeAmount.setScale(2, RoundingMode.HALF_UP);
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "tu operacion" : value;
    }

    private Map<String, String> paymentConfirmedMessageData(
            Integer userId,
            WonBidCore wonBid,
            BigDecimal commissionPct,
            BigDecimal commissionAmount,
            BigDecimal shippingAmount,
            BigDecimal totalToPay,
            boolean retiroPresencial,
            String shippingAddress
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("headline", "Informacion de pago - Subasta Ganada");
        data.put("item_id", String.valueOf(wonBid.itemId()));
        data.put("auction_id", String.valueOf(wonBid.auctionId()));
        data.put("lot_code", defaultText(wonBid.lotCode()));
        data.put("item_title", defaultText(wonBid.title()));
        data.put("auction_name", defaultText(wonBid.auctionName()));
        data.put("winning_bid", formatMoney(wonBid.winningBid()));
        data.put("commission_pct", commissionPct.setScale(2, RoundingMode.HALF_UP).toPlainString());
        data.put("commission_amount", formatMoney(commissionAmount));
        data.put("shipping_amount", retiroPresencial ? "Sin cargo" : formatMoney(shippingAmount));
        if (shippingAddress != null && !shippingAddress.isBlank()) {
            data.put("shipping_address", shippingAddress);
        }
        data.put("total_to_pay", formatMoney(totalToPay));
        data.put("currency", wonBid.auctionCurrency());
        data.put("pickup_selected", retiroPresencial ? "si" : "no");
        data.put("status", "confirmado");
        data.put("cta_label", "Ver compra");
        data.put("cta_target", "/users/%s/won-items/%s/payment".formatted(userId, wonBid.itemId()));
        return data;
    }

    private String ownerAddress(Integer userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT direccion
                    FROM personas
                    WHERE identificador = ?
                    """, String.class, userId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private String buildPaymentReference(
            Integer registroId,
            Integer paymentMethodId,
            boolean retiroPresencial,
            String shippingAddress
    ) {
        String baseReference = "SUBY-PAY-" + registroId + "-" + paymentMethodId;
        if (retiroPresencial) {
            return baseReference + "|RETIRO";
        }

        if (shippingAddress == null || shippingAddress.isBlank()) {
            return baseReference + "|ENVIO";
        }

        return baseReference + "|ENVIO|" + shippingAddress;
    }

    private void sendPaymentConfirmationEmail(
            Integer userId,
            WonBidCore wonBid,
            BigDecimal commissionAmount,
            BigDecimal shippingAmount,
            BigDecimal totalToPay,
            boolean retiroPresencial,
            String shippingAddress
    ) {
        String recipientEmail = userEmail(userId);
        if (recipientEmail == null || recipientEmail.isBlank()) {
            log.warn("Skipping payment confirmation email because user {} has no email", userId);
            return;
        }

        String deliverySummary = retiroPresencial
                ? "Retiro personal en deposito (sin costo de envio)."
                : "Envio a domicilio%s."
                .formatted(
                        shippingAddress == null || shippingAddress.isBlank()
                                ? ""
                                : " a " + shippingAddress
                );

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipientEmail);
        message.setSubject("Pago confirmado - " + defaultText(wonBid.lotCode()));
        message.setText("""
                Hola,

                Confirmamos el pago de tu compra en Suby.

                Lote: %s
                Articulo: %s
                Subasta: %s
                Puja ganadora: %s
                Comision: %s
                Envio: %s
                Total abonado: %s

                Modalidad de entrega:
                %s

                Podes revisar el detalle completo desde la app.

                Saludos,
                Equipo Suby
                """.formatted(
                defaultText(wonBid.lotCode()),
                defaultText(wonBid.title()),
                defaultText(wonBid.auctionName()),
                formatMoney(wonBid.winningBid(), wonBid.auctionCurrency()),
                formatMoney(commissionAmount, wonBid.auctionCurrency()),
                retiroPresencial ? "Sin cargo" : formatMoney(shippingAmount, wonBid.auctionCurrency()),
                formatMoney(totalToPay, wonBid.auctionCurrency()),
                deliverySummary
        ));

        try {
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("Failed to send payment confirmation email to {}", recipientEmail, ex);
        }
    }

    private String userEmail(Integer userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT email
                    FROM usuarios_app
                    WHERE identificador = ?
                    """, String.class, userId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private record WonBidCore(
            Integer itemId,
            String lotCode,
            String title,
            String description,
            String ownerAtSale,
            Integer ownerId,
            Integer auctionId,
            String auctionName,
            LocalDate auctionDate,
            java.time.LocalTime auctionHour,
            String location,
            String auctioneer,
            Integer productId,
            String auctionCurrency,
            BigDecimal winningBid,
            String winner,
            BigDecimal commission,
            BigDecimal basePrice
    ) {
    }

    private record TimelineRow(
            Integer bidId,
            Integer bidderNumber,
            Integer bidderClientId,
            BigDecimal amount
    ) {
    }

    private record PaymentMethodState(
            Integer id,
            String type,
            String status,
            String currency,
            BigDecimal availableBalance,
            boolean internationalCard,
            Integer checkAuctionId,
            Integer requestedAuctionId
    ) {
        boolean canCover(BigDecimal totalToPay, String auctionCurrency) {
            if (!"verificado".equalsIgnoreCase(status)) {
                return false;
            }
            if ("tarjeta_credito".equals(type)) {
                return "ARS".equalsIgnoreCase(auctionCurrency) || internationalCard;
            }
            if (!auctionCurrency.equalsIgnoreCase(currency)) {
                return false;
            }
            if ("cheque_certificado".equals(type)
                    && checkAuctionId != null
                    && requestedAuctionId != null
                    && !checkAuctionId.equals(requestedAuctionId)) {
                return false;
            }
            return availableBalance.compareTo(totalToPay) >= 0;
        }
    }
}
