package com.tpo.suby.service;

import com.tpo.suby.dto.response.home.FeaturedLotResponse;
import com.tpo.suby.dto.response.home.HomeAuctionResponse;
import com.tpo.suby.dto.response.home.HomeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HomeService {

    private final JdbcTemplate jdbcTemplate;

    public HomeResponse getHome() {
        return HomeResponse.builder()
                .featuredLots(getFeaturedLots())
                .upcomingAuctions(getUpcomingAuctions())
                .liveAuctions(getLiveAuctions())
                .build();
    }

    private List<FeaturedLotResponse> getFeaturedLots() {
        String sql = """
                SELECT TOP 10
                    ic.identificador AS item_id,
                    CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                    p.descripcionCompleta AS title,
                    ic.precioBase AS base_price,
                    s.categoria AS category,
                    s.identificador AS auction_id,
                    COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                    ps.nombre AS auctioneer,
                    s.fecha AS auction_date,
                    %s AS status
                FROM itemsCatalogo ic
                JOIN productos p ON p.identificador = ic.producto
                JOIN catalogos c ON c.identificador = ic.catalogo
                JOIN subastas s ON s.identificador = c.subasta
                LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                LEFT JOIN personas ps ON ps.identificador = sub.identificador
                WHERE %s
                  AND (ic.subastado IS NULL OR ic.subastado = 'no')
                ORDER BY s.fecha ASC, ic.precioBase DESC
                """.formatted(
                AuctionStatusSql.normalizedStatusCase("s.estado", "s.fecha"),
                AuctionStatusSql.principalFlowFilter("s.estado", "s.fecha")
        );

        return jdbcTemplate.query(sql, (rs, rowNum) -> FeaturedLotResponse.builder()
                .itemId(rs.getInt("item_id"))
                .lotCode(rs.getString("lot_code"))
                .title(rs.getString("title"))
                .basePrice(rs.getBigDecimal("base_price"))
                .category(rs.getString("category"))
                .auctionId(rs.getInt("auction_id"))
                .auctionName(rs.getString("auction_name"))
                .auctioneer(rs.getString("auctioneer"))
                .auctionDate(toLocalDate(rs.getDate("auction_date")))
                .status(rs.getString("status"))
                .build());
    }

    private List<HomeAuctionResponse> getUpcomingAuctions() {
        String sql = auctionSql("""
                WHERE %s
                  AND CAST(s.fecha AS DATE) > CAST(GETDATE() AS DATE)
                ORDER BY s.fecha ASC, s.hora ASC
                """.formatted(AuctionStatusSql.activeStateFilter("s.estado")));

        return jdbcTemplate.query(sql, (rs, rowNum) -> toAuctionResponse(rs));
    }

    private List<HomeAuctionResponse> getLiveAuctions() {
        String sql = auctionSql("""
                WHERE %s
                  AND CAST(s.fecha AS DATE) = CAST(GETDATE() AS DATE)
                ORDER BY s.hora ASC
                """.formatted(AuctionStatusSql.activeStateFilter("s.estado")));

        return jdbcTemplate.query(sql, (rs, rowNum) -> toAuctionResponse(rs));
    }

    private String auctionSql(String filter) {
        return """
                SELECT TOP 10
                    s.identificador AS auction_id,
                    COALESCE(catalogo.descripcion, CONCAT('Subasta ', s.identificador)) AS auction_name,
                    ps.nombre AS auctioneer,
                    s.fecha AS auction_date,
                    s.hora AS auction_time,
                    CASE
                        WHEN %s AND CAST(s.fecha AS DATE) = CAST(GETDATE() AS DATE) THEN 'en_vivo'
                        ELSE 'proxima'
                    END AS status,
                    s.ubicacion AS location,
                    s.categoria AS category,
                    se.moneda AS currency,
                    COALESCE(lotes.lot_count, 0) AS lot_count
                FROM subastas s
                LEFT JOIN subastas_ext se ON se.identificador = s.identificador
                LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                LEFT JOIN personas ps ON ps.identificador = sub.identificador
                OUTER APPLY (
                    SELECT TOP 1 c.descripcion
                    FROM catalogos c
                    WHERE c.subasta = s.identificador
                    ORDER BY c.identificador ASC
                ) catalogo
                OUTER APPLY (
                    SELECT COUNT(ic.identificador) AS lot_count
                    FROM catalogos c
                    JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                    WHERE c.subasta = s.identificador
                ) lotes
                %s
                """.formatted(
                AuctionStatusSql.activeStateFilter("s.estado"),
                filter
        );
    }

    private HomeAuctionResponse toAuctionResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return HomeAuctionResponse.builder()
                .auctionId(rs.getInt("auction_id"))
                .auctionName(rs.getString("auction_name"))
                .auctioneer(rs.getString("auctioneer"))
                .auctionDate(toLocalDate(rs.getDate("auction_date")))
                .auctionTime(toLocalTime(rs.getTime("auction_time")))
                .status(rs.getString("status"))
                .location(rs.getString("location"))
                .category(rs.getString("category"))
                .currency(rs.getString("currency"))
                .lotCount(rs.getInt("lot_count"))
                .build();
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private LocalTime toLocalTime(Time time) {
        return time == null ? null : time.toLocalTime();
    }
}
