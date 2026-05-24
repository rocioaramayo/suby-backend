package com.tpo.suby.service;

import com.tpo.suby.dto.response.user.UserBidHistoryItemResponse;
import com.tpo.suby.dto.response.user.UserBidHistoryResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
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
}
