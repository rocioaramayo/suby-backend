package com.tpo.suby.service;

import java.sql.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.tpo.suby.dto.response.search.SearchAuctionItemResponse;
import com.tpo.suby.dto.response.search.SearchLotItemResponse;
import com.tpo.suby.dto.response.search.SearchResponse;
import com.tpo.suby.exception.InvalidQueryParameterException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {

    private static final String TYPE_AUCTIONS = "auctions";
    private static final String TYPE_LOTS = "lots";
    private static final Set<String> VALID_TYPES = Set.of(TYPE_AUCTIONS, TYPE_LOTS);

    private final JdbcTemplate jdbcTemplate;
    private final AuctionScheduleService auctionScheduleService;

    public SearchResponse search(String query, String type) {
        String normalizedQuery = normalizeQuery(query);
        String normalizedType = normalizeType(type);

        List<SearchAuctionItemResponse> auctions = TYPE_LOTS.equals(normalizedType)
                ? List.of()
                : searchAuctionSessions(normalizedQuery);

        List<SearchLotItemResponse> lots = TYPE_AUCTIONS.equals(normalizedType)
                ? List.of()
                : searchCatalogLots(normalizedQuery);

        return SearchResponse.builder()
                .auctions(auctions)
                .lots(lots)
                .build();
    }

    private List<SearchAuctionItemResponse> searchAuctionSessions(String normalizedQuery) {
        String like = "%" + normalizedQuery + "%";

        return jdbcTemplate.query("""
                SELECT
                    s.identificador AS id,
                    COALESCE(catalogo.descripcion, CONCAT('Subasta ', s.identificador)) AS name,
                    s.estado AS persisted_state,
                    s.fecha AS date,
                    s.hora AS hour
                FROM subastas s
                OUTER APPLY (
                    SELECT TOP 1 c.descripcion
                    FROM catalogos c
                    WHERE c.subasta = s.identificador
                    ORDER BY c.identificador ASC
                ) catalogo
                LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                LEFT JOIN personas auctioneer ON auctioneer.identificador = sub.identificador
                WHERE
                    LOWER(COALESCE(catalogo.descripcion, '')) LIKE ?
                    OR LOWER(COALESCE(auctioneer.nombre, '')) LIKE ?
                    OR LOWER(COALESCE(s.ubicacion, '')) LIKE ?
                    OR LOWER(COALESCE(s.categoria, '')) LIKE ?
                    OR EXISTS (
                        SELECT 1
                        FROM catalogos c2
                        JOIN itemsCatalogo ic2 ON ic2.catalogo = c2.identificador
                        JOIN productos p2 ON p2.identificador = ic2.producto
                        WHERE c2.subasta = s.identificador
                          AND (
                              LOWER(COALESCE(p2.descripcionCatalogo, '')) LIKE ?
                              OR LOWER(COALESCE(p2.descripcionCompleta, '')) LIKE ?
                          )
                    )
                ORDER BY s.fecha ASC, s.hora ASC
                """, (rs, rowNum) -> SearchAuctionItemResponse.builder()
                .id(rs.getInt("id"))
                .name(rs.getString("name"))
                .status(auctionScheduleService.calculatedStatus(
                        rs.getString("persisted_state"),
                        toLocalDate(rs.getDate("date")),
                        rs.getTime("hour") == null ? null : rs.getTime("hour").toLocalTime()
                ))
                .date(toLocalDate(rs.getDate("date")))
                .build(), like, like, like, like, like, like);
    }

    private List<SearchLotItemResponse> searchCatalogLots(String normalizedQuery) {
        String like = "%" + normalizedQuery + "%";

        return jdbcTemplate.query("""
                SELECT
                    ic.identificador AS item_id,
                    CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                    COALESCE(p.descripcionCatalogo, p.descripcionCompleta) AS title,
                    s.identificador AS auction_id,
                    pd.categoriaTematica AS category,
                    COALESCE(ic.subastado, 'no') AS auctioned
                FROM itemsCatalogo ic
                JOIN productos p ON p.identificador = ic.producto
                JOIN catalogos c ON c.identificador = ic.catalogo
                JOIN subastas s ON s.identificador = c.subasta
                LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                LEFT JOIN duenios d ON d.identificador = p.duenio
                LEFT JOIN personas owner ON owner.identificador = d.identificador
                WHERE
                    COALESCE(ic.subastado, 'no') <> 'si'
                    AND (
                        LOWER(COALESCE(p.descripcionCatalogo, '')) LIKE ?
                        OR LOWER(COALESCE(p.descripcionCompleta, '')) LIKE ?
                        OR LOWER(COALESCE(c.descripcion, '')) LIKE ?
                        OR LOWER(COALESCE(owner.nombre, '')) LIKE ?
                        OR LOWER(CONCAT('lot-', RIGHT(CONCAT('000', ic.identificador), 3))) LIKE ?
                    )
                ORDER BY s.fecha ASC, ic.identificador ASC
                """, (rs, rowNum) -> SearchLotItemResponse.builder()
                .itemId(rs.getInt("item_id"))
                .lotCode(rs.getString("lot_code"))
                .title(rs.getString("title"))
                .auctionId(rs.getInt("auction_id"))
                .category(rs.getString("category"))
                .auctioned(rs.getString("auctioned"))
                .build(), like, like, like, like, like);
    }

    private String normalizeQuery(String query) {
        if (query == null || query.trim().length() < 2) {
            throw new InvalidQueryParameterException("El texto de búsqueda debe tener al menos 2 caracteres.");
        }
        return query.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "all";
        }

        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (!VALID_TYPES.contains(normalized)) {
            return "all";
        }
        return normalized;
    }

    private java.time.LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
