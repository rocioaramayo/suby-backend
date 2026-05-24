package com.tpo.suby.service;

import com.tpo.suby.dto.response.user.UserBidHistoryItemResponse;
import com.tpo.suby.dto.response.user.UserBidHistoryResponse;
import com.tpo.suby.dto.response.user.WonBidAuctionResponse;
import com.tpo.suby.dto.response.user.WonBidDetailResponse;
import com.tpo.suby.dto.response.user.WonBidItemResponse;
import com.tpo.suby.dto.response.user.WonBidResultResponse;
import com.tpo.suby.dto.response.user.WonBidTimelineItemResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.exception.WonBidDetailForbiddenException;
import com.tpo.suby.exception.WonBidDetailNotFoundException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserBidService {

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;

    public UserBidHistoryResponse getBidHistory(Integer userId) {
        validateOwner(userId);

        List<UserBidHistoryItemResponse> bids = jdbcTemplate.query("""
                SELECT
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

    private WonBidCore wonBidCore(Integer userId, Integer itemId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        ic.identificador AS item_id,
                        CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                        p.descripcionCatalogo AS title,
                        p.descripcionCompleta AS description,
                        owner.nombre AS owner_at_sale,
                        s.identificador AS auction_id,
                        COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                        s.fecha AS auction_date,
                        s.hora AS auction_hour,
                        s.ubicacion AS auction_location,
                        auctioneer_person.nombre AS auctioneer,
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
                    rs.getInt("auction_id"),
                    rs.getString("auction_name"),
                    toLocalDate(rs.getDate("auction_date")),
                    rs.getTime("auction_hour").toLocalTime(),
                    rs.getString("auction_location"),
                    rs.getString("auctioneer"),
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
                        bidderLabel += " (Tú)";
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

    private record WonBidCore(
            Integer itemId,
            String lotCode,
            String title,
            String description,
            String ownerAtSale,
            Integer auctionId,
            String auctionName,
            LocalDate auctionDate,
            java.time.LocalTime auctionHour,
            String location,
            String auctioneer,
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
}
