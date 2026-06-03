package com.tpo.suby.service;

import com.tpo.suby.dto.request.bid.BidRequest;
import com.tpo.suby.dto.response.bid.AttendeeRegistrationResponse;
import com.tpo.suby.dto.response.bid.BidResultResponse;
import com.tpo.suby.dto.response.bid.BidResponse;
import com.tpo.suby.dto.response.bid.LiveBidStatusResponse;
import com.tpo.suby.dto.response.bid.WinnerResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.AdjudicatedLotException;
import com.tpo.suby.exception.AuctionRoomAccessException;
import com.tpo.suby.exception.BidRestrictedException;
import com.tpo.suby.exception.BidResultNotFoundException;
import com.tpo.suby.exception.InsufficientBalanceException;
import com.tpo.suby.exception.InvalidBidAmountException;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BidRoomService {

    private static final int AUCTION_DURATION_MINUTES = 210;

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;

    @Transactional
    public AttendeeRegistrationResponse registerAttendee(Integer auctionId) {
        if (auctionId == null || auctionId <= 0) {
            throw new NotFoundException("Subasta no encontrada.");
        }

        UsuarioApp user = authenticatedUser();
        ClientInfo client = clientInfo(user.getIdentificador());
        AuctionInfo auction = auctionInfo(auctionId);

        if (!"activo".equalsIgnoreCase(user.getEstadoApp())
                || !"si".equalsIgnoreCase(client.admitted())) {
            throw new AuctionRoomAccessException("No access to auction room.");
        }

        if (categoryRank(auction.category()) > categoryRank(client.category())) {
            return observerAccess(
                    auctionId,
                    client.id(),
                    "Tu categoría actual no habilita la puja en esta subasta. Podés ingresar como observador."
            );
        }

        if (!hasPaymentMethod(client.id())) {
            return observerAccess(
                    auctionId,
                    client.id(),
                    "Necesitás registrar un medio de pago para pujar. Podés ingresar como observador."
            );
        }

        if (attendeeExists(auctionId, client.id())) {
            return existingAttendee(auctionId, client.id());
        }

        Integer bidderNumber = nextBidderNumber(auctionId);
        Integer attendeeId = insertAttendee(auctionId, client.id(), bidderNumber);

        return AttendeeRegistrationResponse.builder()
                .attendeeId(attendeeId)
                .bidderNumber(bidderNumber)
                .auctionId(auctionId)
                .clientId(client.id())
                .accessMode("bidder")
                .canBid(true)
                .readOnlyReason(null)
                .build();
    }

    @Transactional
    public BidResponse placeBid(Integer auctionId, Integer itemId, BidRequest request) {
        if (auctionId == null || auctionId <= 0 || itemId == null || itemId <= 0) {
            throw new NotFoundException("Lote no encontrado.");
        }
        if (request == null || request.getAttendeeId() == null || request.getAmount() == null) {
            throw invalidAmount(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        UsuarioApp user = authenticatedUser();
        ClientInfo client = clientInfo(user.getIdentificador());

        if (hasBidRestrictions(user, client.id())) {
            throw new BidRestrictedException("Bid restricted.");
        }

        AttendeeInfo attendee = attendeeInfo(request.getAttendeeId(), auctionId, client.id());
        LotBidInfo lot = lotBidInfo(auctionId, itemId);

        if ("si".equalsIgnoreCase(lot.auctioned())) {
            throw new AdjudicatedLotException("Lot already adjudicated.");
        }

        BigDecimal currentOffer = currentOffer(itemId, lot.basePrice());
        BigDecimal minimum = currentOffer.add(percent(lot.basePrice(), "0.01"));
        BigDecimal maximum = hasNoMaximum(lot.auctionCategory())
                ? null
                : currentOffer.add(percent(lot.basePrice(), "0.20"));

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.compareTo(minimum) < 0
                || (maximum != null && amount.compareTo(maximum) > 0)) {
            throw invalidAmount(minimum, maximum);
        }

        if (availableBalance(client.id()).compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }

        Integer bidId = insertBid(attendee.id(), itemId, amount);

        return BidResponse.builder()
                .bidId(bidId)
                .amount(amount)
                .itemId(itemId)
                .winner("no")
                .newMinimum(amount.add(percent(lot.basePrice(), "0.01")))
                .newMaximum(hasNoMaximum(lot.auctionCategory()) ? null : amount.add(percent(lot.basePrice(), "0.20")))
                .build();
    }

    public LiveBidStatusResponse liveBidStatus(Integer auctionId, Integer itemId) {
        if (auctionId == null || auctionId <= 0 || itemId == null || itemId <= 0) {
            throw new NotFoundException("Lote no encontrado.");
        }

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        ic.identificador AS item_id,
                        CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                        ic.precioBase AS base_price,
                        COALESCE(ic.subastado, 'no') AS auctioned,
                        s.categoria AS auction_category,
                        s.fecha AS auction_date,
                        s.hora AS auction_time,
                        COALESCE(stats.current_offer, ic.precioBase) AS current_offer,
                        COALESCE(stats.total_bids, 0) AS total_bids,
                        bidder.nombre AS last_bidder_name
                    FROM subastas s
                    JOIN catalogos c ON c.subasta = s.identificador
                    JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                    OUTER APPLY (
                        SELECT
                            MAX(pu.importe) AS current_offer,
                            COUNT(pu.identificador) AS total_bids,
                            MAX(pu.identificador) AS last_bid_id
                        FROM pujos pu
                        WHERE pu.item = ic.identificador
                    ) stats
                    LEFT JOIN pujos last_bid ON last_bid.identificador = stats.last_bid_id
                    LEFT JOIN asistentes a ON a.identificador = last_bid.asistente
                    LEFT JOIN personas bidder ON bidder.identificador = a.cliente
                    WHERE s.identificador = ?
                      AND ic.identificador = ?
                    """, (rs, rowNum) -> {
                BigDecimal basePrice = rs.getBigDecimal("base_price");
                BigDecimal currentOffer = rs.getBigDecimal("current_offer");
                String auctionCategory = rs.getString("auction_category");
                LocalDateTime auctionEnd = LocalDateTime.of(
                        toLocalDate(rs.getDate("auction_date")),
                        toLocalTime(rs.getTime("auction_time"))
                ).plusMinutes(AUCTION_DURATION_MINUTES);

                return LiveBidStatusResponse.builder()
                        .itemId(rs.getInt("item_id"))
                        .lotCode(rs.getString("lot_code"))
                        .currentOffer(currentOffer)
                        .totalBids(rs.getInt("total_bids"))
                        .lastBidder(formatBidderName(rs.getString("last_bidder_name")))
                        .secondsRemaining(Math.max(0, Duration.between(LocalDateTime.now(), auctionEnd).toSeconds()))
                        .minimumNextBid(currentOffer.add(percent(basePrice, "0.01")))
                        .maximumNextBid(hasNoMaximum(auctionCategory) ? null : currentOffer.add(percent(basePrice, "0.20")))
                        .auctioned(rs.getString("auctioned"))
                        .build();
            }, auctionId, itemId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Lote no encontrado.");
        }
    }

    public BidResultResponse bidResult(Integer auctionId, Integer itemId) {
        if (auctionId == null || auctionId <= 0 || itemId == null || itemId <= 0) {
            throw new BidResultNotFoundException("Bid result not found.");
        }

        authenticatedUser();

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        ic.identificador AS item_id,
                        CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                        p.descripcionCompleta AS title,
                        ic.precioBase AS base_price,
                        ic.comision AS commission,
                        COALESCE(ic.subastado, 'no') AS auctioned,
                        winning_bid.importe AS winning_bid,
                        winner.numeroPostor AS bidder_number,
                        winner_person.nombre AS winner_name,
                        COALESCE(stats.total_bids, 0) AS total_bids,
                        s.identificador AS auction_id,
                        COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                        auctioneer_person.nombre AS auctioneer,
                        s.fecha AS auction_date,
                        s.hora AS auction_time
                    FROM subastas s
                    JOIN catalogos c ON c.subasta = s.identificador
                    JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                    JOIN productos p ON p.identificador = ic.producto
                    OUTER APPLY (
                        SELECT TOP 1 pu.identificador, pu.importe, pu.asistente
                        FROM pujos pu
                        WHERE pu.item = ic.identificador
                          AND pu.ganador = 'si'
                        ORDER BY pu.importe DESC, pu.identificador DESC
                    ) marked_winner
                    OUTER APPLY (
                        SELECT TOP 1 pu.identificador, pu.importe, pu.asistente
                        FROM pujos pu
                        WHERE pu.item = ic.identificador
                        ORDER BY pu.importe DESC, pu.identificador DESC
                    ) highest_bid
                    OUTER APPLY (
                        SELECT
                            COALESCE(marked_winner.identificador, highest_bid.identificador) AS identificador,
                            COALESCE(marked_winner.importe, highest_bid.importe) AS importe,
                            COALESCE(marked_winner.asistente, highest_bid.asistente) AS asistente
                    ) winning_bid
                    OUTER APPLY (
                        SELECT COUNT(pu.identificador) AS total_bids
                        FROM pujos pu
                        WHERE pu.item = ic.identificador
                    ) stats
                    LEFT JOIN asistentes winner ON winner.identificador = winning_bid.asistente
                    LEFT JOIN personas winner_person ON winner_person.identificador = winner.cliente
                    LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                    LEFT JOIN personas auctioneer_person ON auctioneer_person.identificador = sub.identificador
                    WHERE s.identificador = ?
                      AND ic.identificador = ?
                      AND COALESCE(ic.subastado, 'no') = 'si'
                      AND winning_bid.identificador IS NOT NULL
                    """, (rs, rowNum) -> {
                BigDecimal winningBid = rs.getBigDecimal("winning_bid");
                BigDecimal commissionPercentage = commissionPercentage(
                        rs.getBigDecimal("commission"),
                        rs.getBigDecimal("base_price")
                );
                BigDecimal commissionAmount = winningBid
                        .multiply(commissionPercentage)
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

                LocalDateTime auctionedAt = LocalDateTime.of(
                        toLocalDate(rs.getDate("auction_date")),
                        toLocalTime(rs.getTime("auction_time"))
                ).plusMinutes(AUCTION_DURATION_MINUTES);

                return BidResultResponse.builder()
                        .itemId(rs.getInt("item_id"))
                        .lotCode(rs.getString("lot_code"))
                        .title(rs.getString("title"))
                        .winningBid(winningBid)
                        .winner(WinnerResponse.builder()
                                .bidderNumber(rs.getInt("bidder_number"))
                                .name(formatBidderName(rs.getString("winner_name")))
                                .build())
                        .commissionPercentage(commissionPercentage)
                        .commissionAmount(commissionAmount)
                        .totalToPay(winningBid.add(commissionAmount))
                        .totalBids(rs.getInt("total_bids"))
                        .auctionId(rs.getInt("auction_id"))
                        .auctionName(rs.getString("auction_name"))
                        .auctioneer(rs.getString("auctioneer"))
                        .auctionedAt(auctionedAt.atZone(ZoneId.systemDefault()).toInstant().toString())
                        .build();
            }, auctionId, itemId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BidResultNotFoundException("Bid result not found.");
        }
    }

    private UsuarioApp authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("Debes iniciar sesión para ingresar a la sala de puja.");
        }

        return usuarioAppRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Debes iniciar sesión para ingresar a la sala de puja."));
    }

    private ClientInfo clientInfo(Integer userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT identificador, admitido, categoria
                    FROM clientes
                    WHERE identificador = ?
                    """, (rs, rowNum) -> new ClientInfo(
                    rs.getInt("identificador"),
                    rs.getString("admitido"),
                    rs.getString("categoria")
            ), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new AuctionRoomAccessException("Client profile missing.");
        }
    }

    private AuctionInfo auctionInfo(Integer auctionId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT identificador, categoria
                    FROM subastas
                    WHERE identificador = ?
                    """, (rs, rowNum) -> new AuctionInfo(
                    rs.getInt("identificador"),
                    rs.getString("categoria")
            ), auctionId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Subasta no encontrada.");
        }
    }

    private AttendeeInfo attendeeInfo(Integer attendeeId, Integer auctionId, Integer clientId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT identificador, cliente, subasta
                    FROM asistentes
                    WHERE identificador = ?
                      AND subasta = ?
                      AND cliente = ?
                    """, (rs, rowNum) -> new AttendeeInfo(
                    rs.getInt("identificador"),
                    rs.getInt("cliente"),
                    rs.getInt("subasta")
            ), attendeeId, auctionId, clientId);
        } catch (EmptyResultDataAccessException ex) {
            throw new AuctionRoomAccessException("Attendee does not belong to client.");
        }
    }

    private LotBidInfo lotBidInfo(Integer auctionId, Integer itemId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        ic.identificador AS item_id,
                        ic.precioBase AS base_price,
                        COALESCE(ic.subastado, 'no') AS auctioned,
                        s.categoria AS auction_category
                    FROM subastas s
                    JOIN catalogos c ON c.subasta = s.identificador
                    JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                    WHERE s.identificador = ?
                      AND ic.identificador = ?
                    """, (rs, rowNum) -> new LotBidInfo(
                    rs.getInt("item_id"),
                    rs.getBigDecimal("base_price"),
                    rs.getString("auctioned"),
                    rs.getString("auction_category")
            ), auctionId, itemId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Lote no encontrado.");
        }
    }

    private boolean attendeeExists(Integer auctionId, Integer clientId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM asistentes
                WHERE subasta = ? AND cliente = ?
                """, Integer.class, auctionId, clientId);
        return count != null && count > 0;
    }

    private boolean hasPaymentMethod(Integer clientId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mediosDePago
                WHERE cliente = ?
                """, Integer.class, clientId);
        return count != null && count > 0;
    }

    private boolean hasBidRestrictions(UsuarioApp user, Integer clientId) {
        if (!"activo".equalsIgnoreCase(user.getEstadoApp())) {
            return true;
        }

        Integer pendingFines = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM multas
                WHERE cliente = ?
                  AND estado IN ('pendiente', 'vencida', 'judicial')
                """, Integer.class, clientId);
        return pendingFines != null && pendingFines > 0;
    }

    private BigDecimal currentOffer(Integer itemId, BigDecimal basePrice) {
        BigDecimal latest = jdbcTemplate.queryForObject("""
                SELECT MAX(importe)
                FROM pujos
                WHERE item = ?
                """, BigDecimal.class, itemId);
        return latest == null ? basePrice : latest;
    }

    private BigDecimal availableBalance(Integer clientId) {
        BigDecimal balance = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(montoDisponible, 0) - COALESCE(montoUsado, 0)), 0)
                FROM mediosDePago
                WHERE cliente = ?
                  AND estado IN ('pendiente', 'verificado')
                """, BigDecimal.class, clientId);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    private Integer nextBidderNumber(Integer auctionId) {
        Integer next = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(numeroPostor), 0) + 1
                FROM asistentes
                WHERE subasta = ?
                """, Integer.class, auctionId);
        return next == null ? 1 : next;
    }

    private Integer insertAttendee(Integer auctionId, Integer clientId, Integer bidderNumber) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO asistentes (numeroPostor, cliente, subasta)
                    VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, bidderNumber);
            ps.setInt(2, clientId);
            ps.setInt(3, auctionId);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new AuctionRoomAccessException("Could not register attendee.");
        }
        return key.intValue();
    }

    private AttendeeRegistrationResponse existingAttendee(Integer auctionId, Integer clientId) {
        return jdbcTemplate.queryForObject("""
                SELECT identificador, numeroPostor
                FROM asistentes
                WHERE subasta = ?
                  AND cliente = ?
                """, (rs, rowNum) -> AttendeeRegistrationResponse.builder()
                .attendeeId(rs.getInt("identificador"))
                .bidderNumber(rs.getInt("numeroPostor"))
                .auctionId(auctionId)
                .clientId(clientId)
                .accessMode("bidder")
                .canBid(true)
                .readOnlyReason(null)
                .build(), auctionId, clientId);
    }

    private AttendeeRegistrationResponse observerAccess(Integer auctionId, Integer clientId, String reason) {
        return AttendeeRegistrationResponse.builder()
                .attendeeId(null)
                .bidderNumber(null)
                .auctionId(auctionId)
                .clientId(clientId)
                .accessMode("observer")
                .canBid(false)
                .readOnlyReason(reason)
                .build();
    }

    private Integer insertBid(Integer attendeeId, Integer itemId, BigDecimal amount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO pujos (asistente, item, importe, ganador)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, attendeeId);
            ps.setInt(2, itemId);
            ps.setBigDecimal(3, amount);
            ps.setString(4, "no");
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw invalidAmount(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return key.intValue();
    }

    private InvalidBidAmountException invalidAmount(BigDecimal minimum, BigDecimal maximum) {
        if (maximum == null) {
            return new InvalidBidAmountException(
                    "Ingresá un monto válido. El mínimo requerido es US$ %s."
                            .formatted(formatMoney(minimum))
            );
        }

        return new InvalidBidAmountException(
                "Ingresá un monto válido. El mínimo requerido es US$ %s y el máximo es US$ %s."
                        .formatted(formatMoney(minimum), formatMoney(maximum))
        );
    }

    private BigDecimal percent(BigDecimal base, String percent) {
        return base.multiply(new BigDecimal(percent)).setScale(2, RoundingMode.HALF_UP);
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

    private boolean hasNoMaximum(String category) {
        String normalized = normalize(category);
        return "oro".equals(normalized) || "platino".equals(normalized);
    }

    private String formatMoney(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        return format.format(amount);
    }

    private int categoryRank(String category) {
        return switch (normalize(category)) {
            case "especial" -> 2;
            case "plata" -> 3;
            case "oro" -> 4;
            case "platino" -> 5;
            default -> 1;
        };
    }

    private String normalize(String value) {
        return value == null ? "comun" : value.toLowerCase(Locale.ROOT).trim();
    }

    private java.time.LocalDate toLocalDate(Date date) {
        return date == null ? java.time.LocalDate.now() : date.toLocalDate();
    }

    private java.time.LocalTime toLocalTime(Time time) {
        return time == null ? java.time.LocalTime.MIDNIGHT : time.toLocalTime();
    }

    private String formatBidderName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return "%s %s.".formatted(parts[0], parts[parts.length - 1].substring(0, 1).toUpperCase(Locale.ROOT));
    }

    private record ClientInfo(Integer id, String admitted, String category) {
    }

    private record AuctionInfo(Integer id, String category) {
    }

    private record AttendeeInfo(Integer id, Integer clientId, Integer auctionId) {
    }

    private record LotBidInfo(Integer itemId, BigDecimal basePrice, String auctioned, String auctionCategory) {
    }
}
