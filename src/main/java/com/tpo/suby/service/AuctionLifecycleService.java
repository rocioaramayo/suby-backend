package com.tpo.suby.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuctionLifecycleService {

    private static final int LOT_INACTIVITY_SECONDS = 60;
    private static final BigDecimal ESTIMATED_SHIPPING_AMOUNT = new BigDecimal("150.00");
    private static final String COMPANY_BUYER_DOCUMENT = "SUBY-COMPANY-BUYER";
    private static final String COMPANY_BUYER_NAME = "Suby";
    private static final Logger log = LoggerFactory.getLogger(AuctionLifecycleService.class);

    private final JdbcTemplate jdbcTemplate;
    private final PrivateMessageService privateMessageService;
    private final AuctionLotStateService auctionLotStateService;
    private final AuctionScheduleService auctionScheduleService;

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void closeEndedAuctions() {
        List<AuctionSettlementInfo> candidateAuctions = jdbcTemplate.query("""
                SELECT
                    s.identificador AS auction_id,
                    s.fecha AS auction_date,
                    s.hora AS auction_time,
                    s.estado AS persisted_state,
                    COALESCE(se.moneda, 'ARS') AS currency
                FROM subastas s
                LEFT JOIN subastas_ext se ON se.identificador = s.identificador
                WHERE LOWER(LTRIM(RTRIM(COALESCE(s.estado, '')))) IN ('abierta', 'activa', 'en_vivo', 'live', 'open')
                """, (rs, rowNum) -> new AuctionSettlementInfo(
                rs.getInt("auction_id"),
                rs.getDate("auction_date").toLocalDate(),
                rs.getTime("auction_time") == null ? null : rs.getTime("auction_time").toLocalTime(),
                rs.getString("persisted_state"),
                rs.getString("currency")
        ));

        for (AuctionSettlementInfo auction : candidateAuctions) {
            String calculatedState = auctionScheduleService.calculatedStatus(
                    auction.persistedState(),
                    auction.date(),
                    auction.time()
            );
            if (!"en_vivo".equals(calculatedState)) {
                continue;
            }

            log.info(
                    "auction-lifecycle-progress auctionId={} fechaConfigurada={} horaConfigurada={} fechaHoraInicio={} ahoraArgentina={} estadoPersistido={} estadoCalculado={}",
                    auction.id(),
                    auction.date(),
                    auction.time(),
                    auctionScheduleService.scheduledAt(auction.date(), auction.time()),
                    auctionScheduleService.now(),
                    auction.persistedState(),
                    calculatedState
            );
            progressAuction(auction);
        }
    }

    private void progressAuction(AuctionSettlementInfo auction) {
        Integer activeItemId = auctionLotStateService.currentActiveItemId(auction.id());
        if (activeItemId == null) {
            closeAuction(auction.id());
            return;
        }

        auctionLotStateService.ensureActiveLotState(auction.id(), activeItemId);
        if (!auctionLotStateService.shouldSettle(auction.id(), activeItemId, LOT_INACTIVITY_SECONDS)) {
            return;
        }

        settleItem(auction, activeItemId);
        auctionLotStateService.markClosed(auction.id(), activeItemId);

        if (auctionLotStateService.currentActiveItemId(auction.id()) == null) {
            closeAuction(auction.id());
        }
    }

    private void closeAuction(Integer auctionId) {
        jdbcTemplate.update("""
                UPDATE subastas
                SET estado = 'cerrada'
                WHERE identificador = ?
                """, auctionId);

        jdbcTemplate.update("""
                UPDATE sesiones_usuario
                SET subastaActiva = NULL
                WHERE subastaActiva = ?
                """, auctionId);
    }

    private void settleItem(AuctionSettlementInfo auction, Integer itemId) {
        WinningBidInfo winningBid = highestBid(itemId);
        if (winningBid == null) {
            settleUnsoldItemForCompanyPurchase(auction, itemId);
            return;
        }

        jdbcTemplate.update("""
                UPDATE pujos
                SET ganador = 'no'
                WHERE item = ?
                """, itemId);

        jdbcTemplate.update("""
                UPDATE pujos
                SET ganador = 'si'
                WHERE identificador = ?
                """, winningBid.bidId());

        jdbcTemplate.update("""
                UPDATE itemsCatalogo
                SET subastado = 'si'
                WHERE identificador = ?
                """, itemId);

        transferProductOwnershipIfPossible(winningBid.productId(), winningBid.clientId());

        BigDecimal commissionAmount = commissionAmount(winningBid.commission(), winningBid.basePrice(), winningBid.amount());
        Integer registroId = ensureRegistroSubasta(
                auction.id(),
                winningBid.ownerId(),
                winningBid.productId(),
                winningBid.clientId(),
                winningBid.amount(),
                commissionAmount
        );
        ensurePaymentNotification(
                winningBid.clientId(),
                registroId,
                winningBid.amount(),
                commissionAmount
        );
        ensureWinnerMessage(
                winningBid.clientId(),
                winningBid.amount(),
                commissionAmount,
                winningBid.itemTitle(),
                winningBid.auctionName(),
                auction.currency(),
                winningBid.productId(),
                itemId
        );
        insertPenaltiesIfOverspent(
                winningBid.clientId(),
                winningBid.bidId(),
                winningBid.amount(),
                winningBid.paymentMethodId(),
                winningBid.itemTitle(),
                winningBid.auctionName(),
                auction.currency()
        );
    }

    private void settleUnsoldItemForCompanyPurchase(AuctionSettlementInfo auction, Integer itemId) {
        UnsoldItemInfo item = unsoldItemInfo(itemId);
        if (item == null) {
            jdbcTemplate.update("""
                    UPDATE itemsCatalogo
                    SET subastado = 'si'
                    WHERE identificador = ?
                    """, itemId);
            return;
        }

        Integer companyBuyerId = ensureCompanyBuyerProfile();
        jdbcTemplate.update("""
                UPDATE itemsCatalogo
                SET subastado = 'si'
                WHERE identificador = ?
                """, itemId);

        jdbcTemplate.update("""
                UPDATE productos
                SET duenio = ?
                WHERE identificador = ?
                """, companyBuyerId, item.productId());

        ensureRegistroSubasta(
                auction.id(),
                item.ownerId(),
                item.productId(),
                companyBuyerId,
                item.basePrice(),
                new BigDecimal("0.02")
        );
        ensureCompanyPurchaseMessage(
                item.ownerId(),
                item.itemId(),
                item.productId(),
                item.itemTitle(),
                item.auctionName(),
                item.basePrice()
        );
    }

private WinningBidInfo highestBid(Integer itemId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        pu.identificador AS bid_id,
                        pu.importe AS bid_amount,
                        pmp.id_medio_de_pago AS payment_method_id,
                        a.cliente AS client_id,
                        ic.comision AS item_commission,
                        ic.precioBase AS base_price,
                        p.identificador AS product_id,
                        d.identificador AS owner_id,
                        COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS item_title,
                        COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name
                    FROM pujos pu
                    LEFT JOIN pujos_medios_de_pago pmp ON pmp.id_pujo = pu.identificador 
                    JOIN asistentes a ON a.identificador = pu.asistente
                    JOIN itemsCatalogo ic ON ic.identificador = pu.item
                    JOIN productos p ON p.identificador = ic.producto
                    LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                    LEFT JOIN duenios d ON d.identificador = p.duenio
                    JOIN catalogos c ON c.identificador = ic.catalogo
                    JOIN subastas s ON s.identificador = c.subasta
                    WHERE pu.item = ?
                    ORDER BY pu.importe DESC, pu.identificador DESC
                    """, (rs, rowNum) -> new WinningBidInfo(
                    rs.getInt("bid_id"),
                    rs.getBigDecimal("bid_amount"),
                    rs.getInt("client_id"),
                    rs.getBigDecimal("item_commission"),
                    rs.getBigDecimal("base_price"),
                    rs.getInt("product_id"),
                    rs.getInt("owner_id"),
                    rs.getString("item_title"),
                    rs.getString("auction_name"),
                    nullableInt(rs, "payment_method_id")
            ), itemId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private UnsoldItemInfo unsoldItemInfo(Integer itemId) {
        try {
            return jdbcTemplate.queryForObject("""
                SELECT
                ic.identificador AS item_id,
                ic.precioBase AS base_price,
                ic.comision AS item_commission,
                p.identificador AS product_id,
                d.identificador AS owner_id,
                COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS item_title,
                COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name
                FROM itemsCatalogo ic
                JOIN productos p ON p.identificador = ic.producto
                LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                LEFT JOIN duenios d ON d.identificador = p.duenio
                LEFT JOIN catalogos c ON c.identificador = ic.catalogo
                LEFT JOIN subastas s ON s.identificador = c.subasta
                WHERE ic.identificador = ?
                """, (rs, rowNum) -> new UnsoldItemInfo(
                rs.getInt("item_id"),
                rs.getBigDecimal("base_price"),
                rs.getBigDecimal("item_commission"),
                rs.getInt("product_id"),
                rs.getInt("owner_id"),
                rs.getString("item_title"),
                rs.getString("auction_name")
        ), itemId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private BigDecimal commissionAmount(BigDecimal commission, BigDecimal basePrice, BigDecimal winningAmount) {
        BigDecimal pct;
        if (commission == null || commission.compareTo(BigDecimal.ZERO) <= 0) {
            pct = BigDecimal.ZERO;
        } else if (commission.compareTo(new BigDecimal("100")) <= 0) {
            pct = commission;
        } else {
            pct = commission.multiply(new BigDecimal("100")).divide(basePrice, 2, RoundingMode.HALF_UP);
        }
        return winningAmount.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    private Integer ensureRegistroSubasta(
            Integer auctionId,
            Integer ownerId,
            Integer productId,
            Integer clientId,
            BigDecimal amount,
            BigDecimal commissionAmount
    ) {
        Integer existing = jdbcTemplate.query("""
                SELECT identificador
                FROM registroDeSubasta
                WHERE subasta = ?
                  AND producto = ?
                  AND cliente = ?
                """, rs -> rs.next() ? rs.getInt("identificador") : null, auctionId, productId, clientId);

        if (existing != null) {
            return existing;
        }

        jdbcTemplate.update("""
                INSERT INTO registroDeSubasta (
                    subasta, duenio, producto, cliente, importe, comision
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """, auctionId, ownerId, productId, clientId, amount, commissionAmount);

        return jdbcTemplate.queryForObject("""
                SELECT TOP 1 identificador
                FROM registroDeSubasta
                WHERE subasta = ?
                  AND producto = ?
                  AND cliente = ?
                ORDER BY identificador DESC
                """, Integer.class, auctionId, productId, clientId);
    }

    private void ensurePaymentNotification(Integer clientId, Integer registroId, BigDecimal winningAmount, BigDecimal commissionAmount) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM notificacionesPago
                WHERE cliente = ?
                  AND registro = ?
                """, Integer.class, clientId, registroId);

        if (count != null && count > 0) {
            return;
        }

        BigDecimal shippingCost = ESTIMATED_SHIPPING_AMOUNT.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = winningAmount.add(commissionAmount).add(shippingCost);

        jdbcTemplate.update("""
                INSERT INTO notificacionesPago (
                    cliente, registro, importePujado, comision, costoEnvio, importeTotal, medioDePago
                )
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """, clientId, registroId, winningAmount, commissionAmount, shippingCost, totalAmount);
    }

    private void ensureWinnerMessage(
            Integer clientId,
            BigDecimal winningAmount,
            BigDecimal commissionAmount,
            String itemTitle,
            String auctionName,
            String currency,
            Integer productId,
            Integer itemId
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("headline", "Felicitaciones! Ganaste la subasta");
        data.put("product_id", String.valueOf(productId));
        data.put("item_id", String.valueOf(itemId));
        data.put("lot_code", "LOT-" + String.format("%03d", itemId));
        data.put("item_title", defaultText(itemTitle));
        data.put("auction_name", defaultText(auctionName));
        data.put("winning_bid", formatMoney(winningAmount));
        data.put("commission_amount", formatMoney(commissionAmount));
        data.put("shipping_amount", formatMoney(ESTIMATED_SHIPPING_AMOUNT));
        data.put("total_to_pay", formatMoney(winningAmount.add(commissionAmount).add(ESTIMATED_SHIPPING_AMOUNT)));
        data.put("currency", currency);
        data.put("cta_label", "Ir a pagar");
        data.put("cta_target", "/won-items/%s/payment".formatted(itemId));
        privateMessageService.createPrivateMessage(
                clientId,
                "ganador_subasta",
                "Ganaste la subasta",
                "Ganaste %s en %s. Total informado: %s."
                        .formatted(defaultText(itemTitle), defaultText(auctionName), formatMoney(winningAmount.add(commissionAmount).add(ESTIMATED_SHIPPING_AMOUNT))),
                data
        );
    }

    private void transferProductOwnershipIfPossible(Integer productId, Integer clientId) {
        Integer existsAsOwner = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM duenios
                WHERE identificador = ?
                """, Integer.class, clientId);

        if (existsAsOwner != null && existsAsOwner > 0) {
            jdbcTemplate.update("""
                    UPDATE productos
                    SET duenio = ?
                    WHERE identificador = ?
                    """, clientId, productId);
        }
    }

    private String formatMoney(BigDecimal amount) {
        return "$ " + amount.setScale(2, RoundingMode.HALF_UP);
    }

    private void insertPenaltiesIfOverspent(
            Integer clientId,
            Integer bidId,
            BigDecimal winningAmount,
            Integer paymentMethodId,
            String itemTitle,
            String auctionName,
            String currency
    ) {
        if (paymentMethodId == null) {
            return;
        }

        BigDecimal availableBalance = getWinnerPaymentMethodBalance(paymentMethodId);
        if (availableBalance == null) {
            return;
        }

        if (winningAmount.compareTo(availableBalance) <= 0) {
            return;
        }

        // -- Multa del 10% sobre el precio ganador --
        BigDecimal fineAmount = winningAmount
                .multiply(new BigDecimal("0.10"))
                .setScale(2, RoundingMode.HALF_UP);

        jdbcTemplate.update("""
                INSERT INTO multas (
                    cliente, puja, importeMulta, estado, fechaGeneracion, fechaLimitePago
                )
                VALUES (?, ?, ?, 'pendiente', GETDATE(), DATEADD(HOUR, 72, GETDATE()))
                """, clientId, bidId, fineAmount);

        // -- Diferencia de saldo a pagar aparte --
        BigDecimal differenceAmount = winningAmount.subtract(availableBalance)
                .setScale(2, RoundingMode.HALF_UP);

        insertDifferenceRecord(clientId, bidId, winningAmount, availableBalance, differenceAmount);
        ensureDifferenceMessage(clientId, differenceAmount, winningAmount, availableBalance, itemTitle, auctionName, currency);
    }

    private void insertDifferenceRecord(
            Integer clientId,
            Integer bidId,
            BigDecimal winningAmount,
            BigDecimal availableBalance,
            BigDecimal differenceAmount
    ) {
        // Solo insertar si no existe ya una diferencia para esta puja
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM diferencias_saldo
                WHERE puja = ?
                  AND cliente = ?
                """, Integer.class, bidId, clientId);

        if (existing != null && existing > 0) {
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO diferencias_saldo (
                    cliente, puja, importePuja, saldoDisponible, importeDiferencia,
                    estado, fechaGeneracion, fechaLimitePago
                )
                VALUES (?, ?, ?, ?, ?, 'pendiente', GETDATE(), DATEADD(HOUR, 72, GETDATE()))
                """, clientId, bidId, winningAmount, availableBalance, differenceAmount);
    }

    private void ensureDifferenceMessage(
            Integer clientId,
            BigDecimal differenceAmount,
            BigDecimal winningAmount,
            BigDecimal availableBalance,
            String itemTitle,
            String auctionName,
            String currency
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("headline", "Tenés una diferencia de saldo pendiente");
        data.put("flow_kind", "diferencia_saldo");
        data.put("item_title", defaultText(itemTitle));
        data.put("auction_name", defaultText(auctionName));
        data.put("winning_bid", formatMoney(winningAmount));
        data.put("available_balance", formatMoney(availableBalance));
        data.put("difference_amount", formatMoney(differenceAmount));
        data.put("currency", currency);
        data.put("deadline_hours", "72");
        data.put("cta_label", "Pagar diferencia");
        data.put("cta_target", "/differences");

        privateMessageService.createPrivateMessage(
                clientId,
                "aviso_general",
                "Diferencia de saldo pendiente",
                "Ganaste %s por %s pero tu saldo declarado era %s. Debés abonar la diferencia de %s dentro de las 72 hs para evitar el bloqueo de tu cuenta."
                        .formatted(
                                defaultText(itemTitle),
                                formatMoney(winningAmount),
                                formatMoney(availableBalance),
                                formatMoney(differenceAmount)
                        ),
                data
        );
    }


    private BigDecimal getWinnerPaymentMethodBalance(Integer paymentMethodId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        COALESCE(mdp.montoDisponible, 0)
                        - COALESCE(mdp.montoUsado, 0)
                        - COALESCE((
                            SELECT SUM(pj.importe)
                            FROM pujos pj
                            JOIN pujos_medios_de_pago pmp2 ON pmp2.id_pujo = pj.identificador
                            JOIN asistentes a               ON a.identificador = pj.asistente
                            JOIN itemsCatalogo ic           ON ic.identificador = pj.item
                            LEFT JOIN registroDeSubasta rds ON rds.producto = ic.producto
                                                        AND rds.cliente  = a.cliente
                            LEFT JOIN pagos pg              ON pg.registroSubasta = rds.identificador
                                                        AND pg.medioPago = mdp.identificador
                                                        AND pg.estado IN ('pendiente','procesando','confirmado')
                            WHERE pj.ganador = 'si'
                            AND pmp2.id_medio_de_pago = mdp.identificador
                            AND pg.identificador IS NULL
                        ), 0)
                    FROM mediosDePago mdp
                    WHERE mdp.identificador = ?
                    """, BigDecimal.class, paymentMethodId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "tu operacion" : value;
    }

    private Integer ensureCompanyBuyerProfile() {
        Integer personId = jdbcTemplate.query("""
                SELECT identificador
                FROM personas
                WHERE documento = ?
                """, rs -> rs.next() ? rs.getInt("identificador") : null, COMPANY_BUYER_DOCUMENT);

        if (personId == null) {
            jdbcTemplate.update("""
                    INSERT INTO personas (documento, nombre, direccion, estado, foto)
                    VALUES (?, ?, ?, 'activo', NULL)
                    """, COMPANY_BUYER_DOCUMENT, COMPANY_BUYER_NAME, "Compras internas Suby");

            personId = jdbcTemplate.queryForObject("""
                    SELECT identificador
                    FROM personas
                    WHERE documento = ?
                    """, Integer.class, COMPANY_BUYER_DOCUMENT);
        }

        Integer employeeId = firstEmployeeId();
        Integer countryId = firstCountryId();

        Integer clientCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM clientes
                WHERE identificador = ?
                """, Integer.class, personId);
        if (clientCount == null || clientCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO clientes (identificador, numeroPais, admitido, categoria, verificador)
                    VALUES (?, ?, 'si', 'platino', ?)
                    """, personId, countryId, employeeId);
        }

        Integer ownerCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM duenios
                WHERE identificador = ?
                """, Integer.class, personId);
        if (ownerCount == null || ownerCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO duenios (
                        identificador, numeroPais, verificacionFinanciera,
                        verificacionJudicial, calificacionRiesgo, verificador
                    )
                    VALUES (?, ?, 'si', 'si', 1, ?)
                    """, personId, countryId, employeeId);
        }

        return personId;
    }

    private Integer firstEmployeeId() {
        return jdbcTemplate.queryForObject("""
                SELECT TOP 1 identificador
                FROM empleados
                ORDER BY identificador ASC
                """, Integer.class);
    }

    private Integer firstCountryId() {
        return jdbcTemplate.queryForObject("""
                SELECT TOP 1 numero
                FROM paises
                ORDER BY numero ASC
                """, Integer.class);
    }

    private void ensureCompanyPurchaseMessage(
            Integer ownerId,
            Integer itemId,
            Integer productId,
            String itemTitle,
            String auctionName,
            BigDecimal amount
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("headline", "Sin pujas - Suby compro tu articulo");
        data.put("product_id", String.valueOf(productId));
        data.put("product_name", defaultText(itemTitle));
        data.put("product_code", "LOT-" + String.format("%03d", itemId));
        data.put("item_id", String.valueOf(itemId));
        data.put("auction_name", defaultText(auctionName));
        data.put("sale_amount", formatMoney(amount));
        data.put("summary", "Tu articulo no recibio ofertas durante la sesion. Conforme a las condiciones de la subasta, Suby compro el lote por %s."
                .formatted(formatMoney(amount)));

        privateMessageService.createPrivateMessage(
                ownerId,
                "aviso_general",
                "Sin pujas - Suby compro tu articulo",
                "Tu articulo no recibio ofertas durante la sesion. Conforme a las condiciones de la subasta, Suby compro el lote por %s en %s."
                        .formatted(formatMoney(amount), defaultText(auctionName)),
                data
        );
    }

    private record AuctionSettlementInfo(
            Integer id,
            java.time.LocalDate date,
            java.time.LocalTime time,
            String persistedState,
            String currency
    ) {
    }

    private record WinningBidInfo(
            Integer bidId,
            BigDecimal amount,
            Integer clientId,
            BigDecimal commission,
            BigDecimal basePrice,
            Integer productId,
            Integer ownerId,
            String itemTitle,
            String auctionName,
            Integer paymentMethodId
    ) {
    }

    private record UnsoldItemInfo(
            Integer itemId,
            BigDecimal basePrice,
            BigDecimal commission,
            Integer productId,
            Integer ownerId,
            String itemTitle,
            String auctionName
    ) {
    }
}
