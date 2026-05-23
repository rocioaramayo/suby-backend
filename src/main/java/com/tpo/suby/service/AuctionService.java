package com.tpo.suby.service;

import com.tpo.suby.dto.response.auction.AuctionCatalogItemResponse;
import com.tpo.suby.dto.response.auction.AuctionCatalogResponse;
import com.tpo.suby.dto.response.auction.AuctionDetailResponse;
import com.tpo.suby.dto.response.auction.AuctionListItemResponse;
import com.tpo.suby.dto.response.auction.AuctionListResponse;
import com.tpo.suby.dto.response.auction.AuctioneerResponse;
import com.tpo.suby.exception.AuctionAccessDeniedException;
import com.tpo.suby.exception.InvalidQueryParameterException;
import com.tpo.suby.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final Set<String> VALID_CATEGORIES = Set.of("comun", "especial", "plata", "oro", "platino");
    private static final Set<String> VALID_STATUSES = Set.of("proxima", "en_vivo", "finalizada");
    private static final int AUCTION_DURATION_MINUTES = 210;

    private final JdbcTemplate jdbcTemplate;

    public AuctionListResponse listAuctions(String category, String status, String search, Integer page, Integer perPage) {
        int safePage = page == null ? 1 : page;
        int safePerPage = perPage == null ? 10 : perPage;
        validateListFilters(category, status, safePage, safePerPage);

        List<Object> params = new ArrayList<>();
        String where = buildWhere(category, status, search, params);

        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM subastas s
                LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                LEFT JOIN personas ps ON ps.identificador = sub.identificador
                OUTER APPLY (
                    SELECT TOP 1 c.descripcion
                    FROM catalogos c
                    WHERE c.subasta = s.identificador
                    ORDER BY c.identificador ASC
                ) catalogo
                %s
                """.formatted(where), Integer.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add((safePage - 1) * safePerPage);
        pageParams.add(safePerPage);

        List<AuctionListItemResponse> auctions = jdbcTemplate.query("""
                SELECT
                    s.identificador AS id,
                    COALESCE(catalogo.descripcion, CONCAT('Subasta ', s.identificador)) AS name,
                    s.categoria AS category,
                    s.fecha AS date,
                    CAST(DATEADD(MINUTE, ?, CAST(s.hora AS DATETIME)) AS TIME) AS end_time,
                    CASE
                        WHEN s.estado = 'abierta' AND CAST(s.fecha AS DATE) = CAST(GETDATE() AS DATE) THEN 'en_vivo'
                        WHEN s.estado = 'abierta' AND CAST(s.fecha AS DATE) > CAST(GETDATE() AS DATE) THEN 'proxima'
                        ELSE 'finalizada'
                    END AS status,
                    ps.nombre AS auctioneer,
                    COALESCE(lotes.total_lots, 0) AS total_lots,
                    COALESCE(lotes.sold_lots, 0) AS sold_lots,
                    COALESCE(lotes.active_lots, 0) AS active_lots,
                    CAST(NULL AS VARCHAR(350)) AS thumbnail_url
                FROM subastas s
                LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                LEFT JOIN personas ps ON ps.identificador = sub.identificador
                OUTER APPLY (
                    SELECT TOP 1 c.descripcion
                    FROM catalogos c
                    WHERE c.subasta = s.identificador
                    ORDER BY c.identificador ASC
                ) catalogo
                OUTER APPLY (
                    SELECT
                        COUNT(ic.identificador) AS total_lots,
                        SUM(CASE WHEN ic.subastado = 'si' THEN 1 ELSE 0 END) AS sold_lots,
                        SUM(CASE WHEN ic.subastado IS NULL OR ic.subastado = 'no' THEN 1 ELSE 0 END) AS active_lots
                    FROM catalogos c
                    JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                    WHERE c.subasta = s.identificador
                ) lotes
                %s
                ORDER BY s.fecha ASC, s.hora ASC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """.formatted(where), ps -> {
            int index = 1;
            ps.setInt(index++, AUCTION_DURATION_MINUTES);
            for (Object param : params) {
                ps.setObject(index++, param);
            }
            ps.setInt(index++, (Integer) pageParams.get(pageParams.size() - 2));
            ps.setInt(index, (Integer) pageParams.get(pageParams.size() - 1));
        }, (rs, rowNum) -> AuctionListItemResponse.builder()
                .id(rs.getInt("id"))
                .name(rs.getString("name"))
                .category(rs.getString("category"))
                .date(toLocalDate(rs.getDate("date")))
                .endTime(toLocalTime(rs.getTime("end_time")))
                .status(rs.getString("status"))
                .auctioneer(rs.getString("auctioneer"))
                .totalLots(rs.getInt("total_lots"))
                .soldLots(rs.getInt("sold_lots"))
                .activeLots(rs.getInt("active_lots"))
                .thumbnailUrl(rs.getString("thumbnail_url"))
                .build());

        return AuctionListResponse.builder()
                .auctions(auctions)
                .total(total == null ? 0 : total)
                .page(safePage)
                .perPage(safePerPage)
                .build();
    }

    public AuctionDetailResponse getAuctionDetail(Integer auctionId) {
        if (auctionId == null || auctionId <= 0) {
            throw new NotFoundException("Subasta no encontrada.");
        }

        AuctionDetailResponse detail;
        try {
            detail = jdbcTemplate.queryForObject("""
                    SELECT
                        s.identificador AS id,
                        COALESCE(c.descripcion, CONCAT('Subasta ', s.identificador)) AS name,
                        c.identificador AS catalog_id,
                        c.descripcion AS catalog_description,
                        s.fecha AS date,
                        s.hora AS hour,
                        CAST(DATEADD(MINUTE, ?, CAST(s.hora AS DATETIME)) AS TIME) AS end_time,
                        CASE
                            WHEN s.estado = 'abierta' AND CAST(s.fecha AS DATE) = CAST(GETDATE() AS DATE) THEN 'en_vivo'
                            WHEN s.estado = 'abierta' AND CAST(s.fecha AS DATE) > CAST(GETDATE() AS DATE) THEN 'proxima'
                            ELSE 'finalizada'
                        END AS status,
                        s.categoria AS category,
                        s.ubicacion AS location,
                        sub.identificador AS auctioneer_id,
                        ps.nombre AS auctioneer_name,
                        sub.matricula AS auctioneer_license
                    FROM subastas s
                    LEFT JOIN subastadores sub ON sub.identificador = s.subastador
                    LEFT JOIN personas ps ON ps.identificador = sub.identificador
                    OUTER APPLY (
                        SELECT TOP 1 c.identificador, c.descripcion
                        FROM catalogos c
                        WHERE c.subasta = s.identificador
                        ORDER BY c.identificador ASC
                    ) c
                    WHERE s.identificador = ?
                    """, (rs, rowNum) -> AuctionDetailResponse.builder()
                    .id(rs.getInt("id"))
                    .name(rs.getString("name"))
                    .date(toLocalDate(rs.getDate("date")))
                    .hour(toLocalTime(rs.getTime("hour")))
                    .endTime(toLocalTime(rs.getTime("end_time")))
                    .status(rs.getString("status"))
                    .category(rs.getString("category"))
                    .location(rs.getString("location"))
                    .auctioneer(AuctioneerResponse.builder()
                            .id(nullableInt(rs, "auctioneer_id"))
                            .name(rs.getString("auctioneer_name"))
                            .license(rs.getString("auctioneer_license"))
                            .build())
                    .catalog(AuctionCatalogResponse.builder()
                            .id(nullableInt(rs, "catalog_id"))
                            .description(rs.getString("catalog_description"))
                            .items(List.of())
                            .build())
                    .build(), AUCTION_DURATION_MINUTES, auctionId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Subasta no encontrada.");
        }

        validateAuctionAccess(detail.getCategory());

        List<AuctionCatalogItemResponse> items = jdbcTemplate.query("""
                SELECT
                    ic.identificador AS item_id,
                    CONCAT('LOT-', RIGHT(CONCAT('000', ic.identificador), 3)) AS lot_code,
                    p.descripcionCompleta AS title,
                    CAST(NULL AS VARCHAR(250)) AS attribution,
                    owner.nombre AS owner,
                    ic.precioBase AS base_price
                FROM catalogos c
                JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                JOIN productos p ON p.identificador = ic.producto
                LEFT JOIN duenios d ON d.identificador = p.duenio
                LEFT JOIN personas owner ON owner.identificador = d.identificador
                WHERE c.subasta = ?
                ORDER BY ic.identificador ASC
                """, (rs, rowNum) -> AuctionCatalogItemResponse.builder()
                .itemId(rs.getInt("item_id"))
                .lotCode(rs.getString("lot_code"))
                .title(rs.getString("title"))
                .attribution(rs.getString("attribution"))
                .owner(rs.getString("owner"))
                .basePrice(rs.getBigDecimal("base_price"))
                .build(), auctionId);

        return AuctionDetailResponse.builder()
                .id(detail.getId())
                .name(detail.getName())
                .date(detail.getDate())
                .hour(detail.getHour())
                .endTime(detail.getEndTime())
                .status(detail.getStatus())
                .category(detail.getCategory())
                .location(detail.getLocation())
                .auctioneer(detail.getAuctioneer())
                .catalog(AuctionCatalogResponse.builder()
                        .id(detail.getCatalog().getId())
                        .description(detail.getCatalog().getDescription())
                        .items(items)
                        .build())
                .build();
    }

    private String buildWhere(String category, String status, String search, List<Object> params) {
        List<String> filters = new ArrayList<>();

        if (category != null && !category.isBlank()) {
            filters.add("LOWER(s.categoria) = ?");
            params.add(normalize(category));
        }

        if (status != null && !status.isBlank()) {
            filters.add("""
                    CASE
                        WHEN s.estado = 'abierta' AND CAST(s.fecha AS DATE) = CAST(GETDATE() AS DATE) THEN 'en_vivo'
                        WHEN s.estado = 'abierta' AND CAST(s.fecha AS DATE) > CAST(GETDATE() AS DATE) THEN 'proxima'
                        ELSE 'finalizada'
                    END = ?
                    """);
            params.add(normalize(status));
        }

        if (search != null && !search.isBlank()) {
            filters.add("""
                    (
                        LOWER(COALESCE(catalogo.descripcion, '')) LIKE ?
                        OR LOWER(COALESCE(ps.nombre, '')) LIKE ?
                        OR LOWER(COALESCE(s.ubicacion, '')) LIKE ?
                        OR LOWER(COALESCE(s.categoria, '')) LIKE ?
                    )
                    """);
            String like = "%" + search.toLowerCase(Locale.ROOT).trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }

        return filters.isEmpty() ? "" : "WHERE " + String.join(" AND ", filters);
    }

    private void validateListFilters(String category, String status, int page, int perPage) {
        if (page <= 0 || perPage <= 0 || perPage > 100) {
            throw new InvalidQueryParameterException("Parámetros de consulta inválidos.");
        }

        if (category != null && !category.isBlank() && !VALID_CATEGORIES.contains(normalize(category))) {
            throw new InvalidQueryParameterException("Parámetros de consulta inválidos.");
        }

        if (status != null && !status.isBlank() && !VALID_STATUSES.contains(normalize(status))) {
            throw new InvalidQueryParameterException("Parámetros de consulta inválidos.");
        }
    }

    private void validateAuctionAccess(String auctionCategory) {
        String userCategory = getCurrentUserCategory();
        if (categoryRank(auctionCategory) > categoryRank(userCategory)) {
            throw new AuctionAccessDeniedException(
                    "Esta subasta es exclusiva para usuarios categoría %s. Tu categoría actual es %s."
                            .formatted(displayCategory(auctionCategory), displayCategory(userCategory))
            );
        }
    }

    private String getCurrentUserCategory() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return "comun";
        }

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT COALESCE(c.categoria, 'comun')
                    FROM usuarios_app u
                    LEFT JOIN clientes c ON c.identificador = u.identificador
                    WHERE u.email = ?
                    """, String.class, authentication.getName());
        } catch (EmptyResultDataAccessException ex) {
            return "comun";
        }
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

    private String displayCategory(String category) {
        return normalize(category).toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "comun" : value.toLowerCase(Locale.ROOT).trim();
    }

    private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private LocalTime toLocalTime(Time time) {
        return time == null ? null : time.toLocalTime();
    }
}
