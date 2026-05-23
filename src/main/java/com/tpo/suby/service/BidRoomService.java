package com.tpo.suby.service;

import com.tpo.suby.dto.response.bid.AttendeeRegistrationResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.AttendeeAlreadyRegisteredException;
import com.tpo.suby.exception.AuctionRoomAccessException;
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
}
