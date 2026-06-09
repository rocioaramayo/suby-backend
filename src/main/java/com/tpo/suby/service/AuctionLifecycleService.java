package com.tpo.suby.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuctionLifecycleService {

    private static final int AUCTION_DURATION_MINUTES = 210;
    private static final BigDecimal ESTIMATED_SHIPPING_AMOUNT = new BigDecimal("150.00");

    private final JdbcTemplate jdbcTemplate;
    private final PrivateMessageService privateMessageService;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void closeEndedAuctions() {
        List<AuctionSettlementInfo> endedAuctions = jdbcTemplate.query("""
                SELECT
                    s.identificador AS auction_id,
                    COALESCE(se.moneda, 'ARS') AS currency
                FROM subastas s
                LEFT JOIN subastas_ext se ON se.identificador = s.identificador
                WHERE s.estado = 'abierta'
                  AND DATEADD(MINUTE, ?, CAST(CONCAT(CONVERT(varchar(10), s.fecha, 120), ' ', CONVERT(varchar(8), s.hora, 108)) AS DATETIME)) <= GETDATE()
                """, (rs, rowNum) -> new AuctionSettlementInfo(
                rs.getInt("auction_id"),
                rs.getString("currency")
        ), AUCTION_DURATION_MINUTES);

        for (AuctionSettlementInfo auction : endedAuctions) {
            settleAuction(auction);
        }
    }

    private void settleAuction(AuctionSettlementInfo auction) {
        List<Integer> itemIds = jdbcTemplate.query("""
                SELECT ic.identificador
                FROM catalogos c
                JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                WHERE c.subasta = ?
                  AND COALESCE(ic.subastado, 'no') = 'no'
                ORDER BY ic.identificador ASC
                """, (rs, rowNum) -> rs.getInt("identificador"), auction.id());

        for (Integer itemId : itemIds) {
            settleItem(auction, itemId);
        }

        jdbcTemplate.update("""
                UPDATE subastas
                SET estado = 'cerrada'
                WHERE identificador = ?
                """, auction.id());

        jdbcTemplate.update("""
                UPDATE sesiones_usuario
                SET subastaActiva = NULL
                WHERE subastaActiva = ?
                """, auction.id());
    }

    private void settleItem(AuctionSettlementInfo auction, Integer itemId) {
        WinningBidInfo winningBid = highestBid(itemId);
        if (winningBid == null) {
            jdbcTemplate.update("""
                    UPDATE itemsCatalogo
                    SET subastado = 'si'
                    WHERE identificador = ?
                    """, itemId);
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
    }

    private WinningBidInfo highestBid(Integer itemId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        pu.identificador AS bid_id,
                        pu.importe AS bid_amount,
                        a.cliente AS client_id,
                        ic.comision AS item_commission,
                        ic.precioBase AS base_price,
                        p.identificador AS product_id,
                        d.identificador AS owner_id,
                        COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS item_title,
                        COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name
                    FROM pujos pu
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
        data.put("item_id", String.valueOf(productId));
        data.put("lot_code", "LOT-" + String.format("%03d", itemId));
        data.put("item_title", defaultText(itemTitle));
        data.put("auction_name", defaultText(auctionName));
        data.put("winning_bid", formatMoney(winningAmount));
        data.put("commission_amount", formatMoney(commissionAmount));
        data.put("shipping_amount", formatMoney(ESTIMATED_SHIPPING_AMOUNT));
        data.put("total_to_pay", formatMoney(winningAmount.add(commissionAmount).add(ESTIMATED_SHIPPING_AMOUNT)));
        data.put("currency", currency);
        data.put("cta_label", "Ir a pagar");
        data.put("cta_target", "/won-items/%s/payment".formatted(productId));
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

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "tu operacion" : value;
    }

    private record AuctionSettlementInfo(Integer id, String currency) {
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
            String auctionName
    ) {
    }
}
