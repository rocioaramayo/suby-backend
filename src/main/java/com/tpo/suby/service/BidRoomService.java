package com.tpo.suby.service;

import com.tpo.suby.dto.request.bid.BidRequest;
import com.tpo.suby.dto.response.bid.AttendeeRegistrationResponse;
import com.tpo.suby.dto.response.bid.BidResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.AdjudicatedLotException;
import com.tpo.suby.exception.AttendeeAlreadyRegisteredException;
import com.tpo.suby.exception.AuctionRoomAccessException;
import com.tpo.suby.exception.BidRestrictedException;
import com.tpo.suby.exception.InsufficientBalanceException;
import com.tpo.suby.exception.InvalidBidAmountException;
import com.tpo.suby.exception.MissingPaymentMethodException;
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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BidRoomService {

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
                || !"si".equalsIgnoreCase(client.admitted())
                || categoryRank(auction.category()) > categoryRank(client.category())) {
            throw new AuctionRoomAccessException("No access to auction room.");
        }

        if (attendeeExists(auctionId, client.id())) {
            throw new AttendeeAlreadyRegisteredException("Already registered.");
        }

        if (!hasPaymentMethod(client.id())) {
            throw new MissingPaymentMethodException("Missing payment method.");
        }

        Integer bidderNumber = nextBidderNumber(auctionId);
        Integer attendeeId = insertAttendee(auctionId, client.id(), bidderNumber);

        return AttendeeRegistrationResponse.builder()
                .attendeeId(attendeeId)
                .bidderNumber(bidderNumber)
                .auctionId(auctionId)
                .clientId(client.id())
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
        BigDecimal maximum = hasNoMaximum(client.category())
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
                .newMaximum(hasNoMaximum(client.category()) ? null : amount.add(percent(lot.basePrice(), "0.20")))
                .build();
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
                        COALESCE(ic.subastado, 'no') AS auctioned
                    FROM subastas s
                    JOIN catalogos c ON c.subasta = s.identificador
                    JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                    WHERE s.identificador = ?
                      AND ic.identificador = ?
                    """, (rs, rowNum) -> new LotBidInfo(
                    rs.getInt("item_id"),
                    rs.getBigDecimal("base_price"),
                    rs.getString("auctioned")
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

    private record ClientInfo(Integer id, String admitted, String category) {
    }

    private record AuctionInfo(Integer id, String category) {
    }

    private record AttendeeInfo(Integer id, Integer clientId, Integer auctionId) {
    }

    private record LotBidInfo(Integer itemId, BigDecimal basePrice, String auctioned) {
    }
}
