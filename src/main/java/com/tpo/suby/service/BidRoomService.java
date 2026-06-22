package com.tpo.suby.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Time;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tpo.suby.dto.request.bid.AttendeeRegistrationRequest;
import com.tpo.suby.dto.request.bid.BidRequest;
import com.tpo.suby.dto.response.bid.AttendeeRegistrationResponse;
import com.tpo.suby.dto.response.bid.BidResponse;
import com.tpo.suby.dto.response.bid.BidResultResponse;
import com.tpo.suby.dto.response.bid.LiveBidStatusResponse;
import com.tpo.suby.dto.response.bid.WinnerResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.AdjudicatedLotException;
import com.tpo.suby.exception.AuctionAccessDeniedException;
import com.tpo.suby.exception.AuctionRoomAccessException;
import com.tpo.suby.exception.BidRestrictedException;
import com.tpo.suby.exception.BidResultNotFoundException;
import com.tpo.suby.exception.InsufficientBalanceException;
import com.tpo.suby.exception.InvalidBidAmountException;
import com.tpo.suby.exception.MissingPaymentMethodException;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BidRoomService {

    private static final int LOT_INACTIVITY_SECONDS = 60;

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;
    private final AuctionLotStateService auctionLotStateService;

    @Transactional
    public AttendeeRegistrationResponse registerAttendee(Integer auctionId, AttendeeRegistrationRequest request) {
        if (auctionId == null || auctionId <= 0) {
            throw new NotFoundException("Subasta no encontrada.");
        }

        UsuarioApp user = authenticatedUser();
        ClientInfo client = clientInfo(user.getIdentificador());
        AuctionInfo auction = auctionInfo(auctionId);

        if (!isAuctionStarted(auction.date(), auction.hour(), auction.state())) {
            throw new AuctionRoomAccessException("La subasta todavia no comenzo.");
        }

        if (!"activo".equalsIgnoreCase(user.getEstadoApp())
                || !"si".equalsIgnoreCase(client.admitted())) {
            throw new AuctionRoomAccessException("No access to auction room.");
        }

        ensureSingleActiveAuctionSession(user.getIdentificador(), auction.id());

        Integer paymentMethodId = request == null ? null : request.getPaymentMethodId();
        if (paymentMethodId == null || paymentMethodId <= 0) {
            throw new MissingPaymentMethodException("Elegí un medio de pago antes de ingresar a la subasta.");
        }

        if (categoryRank(auction.category()) > categoryRank(client.category())) {
            upsertActiveSession(user.getIdentificador(), auction.id());
            return observerAccess(
                    auctionId,
                    client.id(),
                    "Tu categoria actual no habilita la puja en esta subasta. Podes ingresar como observador."
            );
        }

        PaymentMethodState selectedPaymentMethod = paymentMethodState(client.id(), paymentMethodId, auction.id(), auction.currency());
        if (!selectedPaymentMethod.canBid()) {
            upsertActiveSession(user.getIdentificador(), auction.id());
            return observerAccess(
                    auctionId,
                    client.id(),
                    selectedPaymentMethod.readOnlyReason()
            );
        }

        BigDecimal activeBasePrice = activeBasePriceForAuction(auctionId);
        if (activeBasePrice != null) {
            BigDecimal availableBalance = selectedPaymentMethod.availableAmount()
                .subtract(selectedPaymentMethod.usedAmount())
                .subtract(selectedPaymentMethod.committedAmount());   // NUEVO
                if (availableBalance.compareTo(activeBasePrice) < 0) {
                    upsertActiveSession(user.getIdentificador(), auction.id());
                    return observerAccess(
                            auctionId,
                            client.id(),
                            "Tu saldo disponible es insuficiente para pujar en esta subasta. Podes ingresar como observador."
                    );
                }
        }

        if (attendeeExists(auctionId, client.id())) {
            upsertActiveSession(user.getIdentificador(), auction.id());
            return existingAttendee(auctionId, client.id(), paymentMethodId);
        }

        Integer bidderNumber = nextBidderNumber(auctionId);
        Integer attendeeId = insertAttendee(auctionId, client.id(), bidderNumber);
        upsertActiveSession(user.getIdentificador(), auction.id());

        return AttendeeRegistrationResponse.builder()
                .attendeeId(attendeeId)
                .bidderNumber(bidderNumber)
                .auctionId(auctionId)
                .clientId(client.id())
                .accessMode("bidder")
                .canBid(true)
                .readOnlyReason(null)
                .paymentMethodId(paymentMethodId)
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
        if (request.getPaymentMethodId() == null || request.getPaymentMethodId() <= 0) {
            throw new MissingPaymentMethodException("Elegí un medio de pago antes de pujar.");
        }

        UsuarioApp user = authenticatedUser();
        ClientInfo client = clientInfo(user.getIdentificador());
        AuctionInfo auction = auctionInfo(auctionId);

        if (!isAuctionStarted(auction.date(), auction.hour(), auction.state())) {
            throw new AuctionRoomAccessException("Este lote todavia no esta habilitado para puja.");
        }

        if (hasBidRestrictions(user, client.id())) {
            throw new BidRestrictedException("Bid restricted.");
        }

        ensureSingleActiveAuctionSession(user.getIdentificador(), auction.id());
        AttendeeInfo attendee = attendeeInfo(request.getAttendeeId(), auctionId, client.id());
        LotBidInfo lot = lotBidInfo(auctionId, itemId);

        if (!auctionLotStateService.isActiveLot(auctionId, itemId)) {
            throw new AuctionRoomAccessException("Este lote todavía no está habilitado para puja.");
        }

        if ("si".equalsIgnoreCase(lot.auctioned())) {
            throw new AdjudicatedLotException("Lot already adjudicated.");
        }

        BigDecimal currentOffer = currentOffer(itemId, lot.basePrice());
        BigDecimal minimum = minimumAllowedBid(currentOffer, lot.basePrice(), lot.auctionCategory());
        BigDecimal maximum = maximumAllowedBid(currentOffer, lot.basePrice(), lot.auctionCategory());

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.compareTo(minimum) < 0
                || (maximum != null && amount.compareTo(maximum) > 0)) {
            throw invalidAmount(minimum, maximum);
        }

        PaymentMethodState paymentMethod = paymentMethodState(client.id(), request.getPaymentMethodId(), auction.id(), auction.currency());
        if (!paymentMethod.canBid()) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }

        Integer bidId = insertBid(attendee.id(), itemId, amount, request.getPaymentMethodId());
        auctionLotStateService.touchActivity(auctionId, itemId);

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
                boolean activeLot = auctionLotStateService.isActiveLot(auctionId, itemId);
                long secondsRemaining = activeLot
                        ? auctionLotStateService.secondsRemaining(auctionId, itemId, LOT_INACTIVITY_SECONDS)
                        : 0;

                return LiveBidStatusResponse.builder()
                        .itemId(rs.getInt("item_id"))
                        .lotCode(rs.getString("lot_code"))
                        .currentOffer(currentOffer)
                        .totalBids(rs.getInt("total_bids"))
                        .lastBidder(formatBidderName(rs.getString("last_bidder_name")))
                        .secondsRemaining(secondsRemaining)
                        .minimumNextBid(minimumAllowedBid(currentOffer, basePrice, auctionCategory))
                        .maximumNextBid(maximumAllowedBid(currentOffer, basePrice, auctionCategory))
                        .auctioned(rs.getString("auctioned"))
                        .activeLot(activeLot)
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
                );

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
            throw new UnauthorizedException("Debes iniciar sesion para ingresar a la sala de puja.");
        }

        return usuarioAppRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Debes iniciar sesion para ingresar a la sala de puja."));
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
                    SELECT
                        s.identificador,
                        s.categoria,
                        COALESCE(se.moneda, 'ARS') AS currency,
                        s.fecha,
                        s.hora,
                        s.estado
                    FROM subastas s
                    LEFT JOIN subastas_ext se ON se.identificador = s.identificador
                    WHERE s.identificador = ?
                    """, (rs, rowNum) -> new AuctionInfo(
                    rs.getInt("identificador"),
                    rs.getString("categoria"),
                    rs.getString("currency"),
                    toLocalDate(rs.getDate("fecha")),
                    toLocalTime(rs.getTime("hora")),
                    rs.getString("estado")
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

    private PaymentMethodState paymentMethodState(Integer clientId, Integer paymentMethodId, Integer auctionId, String auctionCurrency) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        mdp.identificador AS payment_method_id,
                        mdp.tipo AS db_type,
                        mdp.estado AS status,
                        mdp.moneda AS currency,
                        COALESCE(mdp.montoDisponible, 0) AS available_amount,
                        COALESCE(mdp.montoUsado, 0) AS used_amount,
                        COALESCE((
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
                        ), 0) AS committed_amount,
                        COALESCE(tc.esInternacional, 'no') AS international_card,
                        cc.subasta AS check_auction_id
                    FROM mediosDePago mdp
                    LEFT JOIN tarjetasCredito tc ON tc.identificador = mdp.identificador
                    LEFT JOIN chequesCertificados cc ON cc.identificador = mdp.identificador
                    WHERE mdp.identificador = ?
                    AND mdp.cliente = ?
                    """, (rs, rowNum) -> {
                String dbType = rs.getString("db_type");
                String status = rs.getString("status");
                String currency = rs.getString("currency");
                BigDecimal availableAmount = rs.getBigDecimal("available_amount");
                BigDecimal usedAmount = rs.getBigDecimal("used_amount");
                boolean verified = "verificado".equalsIgnoreCase(status);
                boolean compatibleCurrency = "tarjeta_credito".equals(dbType) || auctionCurrency.equalsIgnoreCase(currency);
                Integer checkAuctionId = nullableInt(rs, "check_auction_id");
                boolean compatibleAuction = !"cheque_certificado".equals(dbType)
                        || checkAuctionId == null
                        || checkAuctionId.equals(auctionId);
                boolean internationalCompatible = !"tarjeta_credito".equals(dbType)
                        || "ARS".equalsIgnoreCase(auctionCurrency)
                        || "si".equalsIgnoreCase(rs.getString("international_card"));

                boolean canBid = verified && compatibleCurrency && compatibleAuction && internationalCompatible;
                String reason = null;
                if (!verified) {
                    reason = "Tu medio de pago todavía no fue verificado por el administrador.";
                } else if (!compatibleCurrency) {
                    reason = "El medio de pago elegido no opera en la moneda de esta subasta.";
                } else if (!compatibleAuction) {
                    reason = "El cheque certificado elegido pertenece a otra subasta.";
                } else if (!internationalCompatible) {
                    reason = "La tarjeta elegida no está habilitada para esta subasta en USD.";
                }

                return new PaymentMethodState(
                        rs.getInt("payment_method_id"),
                        dbType,
                        status,
                        availableAmount,
                        usedAmount,
                        rs.getBigDecimal("committed_amount"),
                        canBid,
                        reason
                );
            }, paymentMethodId, clientId);
        } catch (EmptyResultDataAccessException ex) {
            throw new MissingPaymentMethodException("El medio de pago seleccionado no existe en tu cuenta.");
        }
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

    private ActiveSessionInfo activeSession(Integer userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        su.subastaActiva AS auction_id,
                        s.fecha AS auction_date,
                        s.hora AS auction_hour,
                        s.estado AS auction_state
                    FROM sesiones_usuario su
                    LEFT JOIN subastas s ON s.identificador = su.subastaActiva
                    WHERE su.persona = ?
                    """, (rs, rowNum) -> new ActiveSessionInfo(
                    nullableInt(rs, "auction_id"),
                    toLocalDate(rs.getDate("auction_date")),
                    toLocalTime(rs.getTime("auction_hour")),
                    rs.getString("auction_state")
            ), userId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private void ensureSingleActiveAuctionSession(Integer userId, Integer auctionId) {
        ActiveSessionInfo activeSession = activeSession(userId);
        if (activeSession == null || activeSession.auctionId() == null || activeSession.auctionId().equals(auctionId)) {
            return;
        }
        if (isAuctionFinished(activeSession.auctionDate(), activeSession.auctionHour(), activeSession.auctionState())) {
            clearActiveSession(userId);
            return;
        }
        throw new AuctionAccessDeniedException("No podes estar conectado a dos subastas al mismo tiempo.");
    }

    private void upsertActiveSession(Integer userId, Integer auctionId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sesiones_usuario
                WHERE persona = ?
                """, Integer.class, userId);

        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    UPDATE sesiones_usuario
                    SET subastaActiva = ?,
                        conectadoEn = GETDATE()
                    WHERE persona = ?
                    """, auctionId, userId);
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO sesiones_usuario (
                    persona, subastaActiva, conectadoEn, tokenSesion, expira
                )
                VALUES (?, ?, GETDATE(), ?, DATEADD(HOUR, 4, GETDATE()))
                """, userId, auctionId, "auction-session-" + userId + "-" + auctionId);
    }

    private void clearActiveSession(Integer userId) {
        jdbcTemplate.update("""
                UPDATE sesiones_usuario
                SET subastaActiva = NULL
                WHERE persona = ?
                """, userId);
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

    private AttendeeRegistrationResponse existingAttendee(Integer auctionId, Integer clientId, Integer paymentMethodId) {
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
                .paymentMethodId(paymentMethodId)
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
                .paymentMethodId(null)
                .build();
    }

    private Integer insertBid(Integer attendeeId, Integer itemId, BigDecimal amount, Integer paymentMethodId) {
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
            
            Integer pujoId = key.intValue();

            
            if (paymentMethodId != null) {
                jdbcTemplate.update("""
                        INSERT INTO pujos_medios_de_pago (id_pujo, id_medio_de_pago)
                        VALUES (?, ?)
                        """, pujoId, paymentMethodId);
            }

            
            return pujoId;
        }

    private BigDecimal activeBasePriceForAuction(Integer auctionId) {
        Integer activeItemId = auctionLotStateService.currentActiveItemId(auctionId);
        if (activeItemId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT ic.precioBase
                    FROM itemsCatalogo ic
                    WHERE ic.identificador = ?
                    """, BigDecimal.class, activeItemId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private InvalidBidAmountException invalidAmount(BigDecimal minimum, BigDecimal maximum) {
        if (maximum == null) {
            return new InvalidBidAmountException(
                    "Ingresa un monto valido. El minimo requerido es US$ %s."
                            .formatted(formatMoney(minimum))
            );
        }

        return new InvalidBidAmountException(
                "Ingresa un monto valido. El minimo requerido es US$ %s y el maximo es US$ %s."
                        .formatted(formatMoney(minimum), formatMoney(maximum))
        );
    }

    private BigDecimal percent(BigDecimal base, String percent) {
        return base.multiply(new BigDecimal(percent)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal minimumAllowedBid(BigDecimal currentOffer, BigDecimal basePrice, String auctionCategory) {
        if (hasNoMaximum(auctionCategory)) {
            return currentOffer.add(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);
        }

        return currentOffer.add(percent(basePrice, "0.01"));
    }

    private BigDecimal maximumAllowedBid(BigDecimal currentOffer, BigDecimal basePrice, String auctionCategory) {
        if (hasNoMaximum(auctionCategory)) {
            return null;
        }

        return currentOffer.add(percent(basePrice, "0.20"));
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

    private boolean isAuctionFinished(java.time.LocalDate auctionDate, java.time.LocalTime auctionTime, String auctionState) {
        return "cerrada".equalsIgnoreCase(auctionState);
    }

    private boolean isAuctionStarted(LocalDate auctionDate, LocalTime auctionTime, String state) {
        // Si el estado ya es 'en_vivo' o 'abierta', el admin la abrió explícitamente → siempre permitir
        String normalizedState = state != null ? state.trim() : "";
        if ("en_vivo".equalsIgnoreCase(normalizedState) || "abierta".equalsIgnoreCase(normalizedState)) {
            return true;
        }
        // Si el estado es 'proxima' o 'cerrada', verificar por fecha/hora como fallback
        LocalDateTime startsAt = LocalDateTime.of(
                auctionDate == null ? LocalDate.now() : auctionDate,
                auctionTime == null ? LocalTime.MIDNIGHT : auctionTime
        );
        return !startsAt.isAfter(LocalDateTime.now());
    }

    private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
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

    private record AuctionInfo(
            Integer id,
            String category,
            String currency,
            java.time.LocalDate date,
            java.time.LocalTime hour,
            String state
    ) {
    }

    private record AttendeeInfo(Integer id, Integer clientId, Integer auctionId) {
    }

    private record LotBidInfo(Integer itemId, BigDecimal basePrice, String auctioned, String auctionCategory) {
    }

    private record PaymentMethodState(
        Integer id,
        String dbType,
        String status,
        BigDecimal availableAmount,
        BigDecimal usedAmount,
        BigDecimal committedAmount,
        boolean canBid,
        String readOnlyReason
) {
    private boolean hasFundsFor(BigDecimal amount) {
        if ("tarjeta_credito".equals(dbType) || "cuenta_bancaria".equals(dbType) || "cheque_certificado".equals(dbType)) {
            return availableAmount.subtract(usedAmount).subtract(committedAmount).compareTo(amount) >= 0;
        }
        return false;
    }
}

    private record ActiveSessionInfo(
            Integer auctionId,
            java.time.LocalDate auctionDate,
            java.time.LocalTime auctionHour,
            String auctionState
    ) {
    }
}