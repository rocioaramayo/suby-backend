package com.tpo.suby.service;

import java.io.IOException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.tpo.suby.dto.response.user.OwnerProductDepositResponse;
import com.tpo.suby.dto.response.user.OwnerProductItemResponse;
import com.tpo.suby.dto.response.user.OwnerProductsResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.InsufficientProductPhotosException;
import com.tpo.suby.exception.OwnerProductValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserProductService {

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;

    public OwnerProductsResponse listOwnerProducts(Integer userId) {
        Integer authenticatedUserId = resolveAuthenticatedOwnerId(userId);

        if (!ownerProfileExists(authenticatedUserId)) {
            return OwnerProductsResponse.builder()
                    .products(List.of())
                    .total(0)
                    .accepted(0)
                    .build();
        }

        List<OwnerProductItemResponse> products = jdbcTemplate.query(ownerProductsSql(),
                (rs, rowNum) -> OwnerProductItemResponse.builder()
                .productId(rs.getInt("product_id"))
                .name(rs.getString("name"))
                .category(rs.getString("category"))
                .dateRegistered(toLocalDate(rs.getDate("date_registered")))
                .inspectionStatus(rs.getString("inspection_status"))
                .available(rs.getString("available"))
                .insurancePolicy(rs.getString("insurance_policy"))
                .insurancePhone(rs.getString("insurance_phone"))
                .deposit(rs.getObject("deposit_id") == null ? null : OwnerProductDepositResponse.builder()
                        .id(rs.getInt("deposit_id"))
                        .name(rs.getString("deposit_name"))
                        .address(rs.getString("deposit_address"))
                        .build())
                .estimatedValue(rs.getBigDecimal("estimated_value"))
                .catalogDescription(rs.getString("catalog_description"))
                .thumbnailUrl(firstOwnerProductPhotoUrl(authenticatedUserId, rs.getInt("product_id")))
                .build(), authenticatedUserId);

        int accepted = (int) products.stream()
                .filter(product -> "aceptado".equalsIgnoreCase(product.getInspectionStatus()))
                .count();

        return OwnerProductsResponse.builder()
                .products(products)
                .total(products.size())
                .accepted(accepted)
                .build();
    }

    private String ownerProductsSql() {
        return """
                SELECT
                    p.identificador AS product_id,
                    COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS name,
                    CASE
                        WHEN LOWER(COALESCE(pd.esObraDeArte, 'no')) = 'si' THEN 'arte'
                        WHEN s.identificador IS NOT NULL THEN s.categoria
                        ELSE 'general'
                    END AS category,
                    p.fecha AS date_registered,
                    CASE
                        WHEN LOWER(COALESCE(last_request.estado, '')) = 'rechazado' THEN 'rechazado'
                        WHEN LOWER(COALESCE(last_request.estado, '')) = 'propuesto' THEN 'propuesto'
                        WHEN ic.identificador IS NOT NULL THEN 'aceptado'
                        WHEN LOWER(COALESCE(last_request.estado, '')) = 'aceptado' THEN 'aceptado'
                        WHEN LOWER(COALESCE(last_request.estado, '')) = 'en_revision' THEN 'en_revision'
                        ELSE 'en_inspeccion'
                    END AS inspection_status,
                    COALESCE(p.disponible, 'no') AS available,
                    COALESCE(pe.nroPoliza, p.seguro) AS insurance_policy,
                    %s AS insurance_phone,
                    dep.identificador AS deposit_id,
                    dep.nombre AS deposit_name,
                    dep.direccion AS deposit_address,
                    COALESCE(ic.precioBase, seg.importe, 0) AS estimated_value,
                    p.descripcionCatalogo AS catalog_description
                FROM productos p
                LEFT JOIN productos_ext pe ON pe.identificador = p.identificador
                LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                LEFT JOIN depositos dep ON dep.identificador = pe.deposito
                LEFT JOIN seguros seg ON seg.nroPoliza = COALESCE(pe.nroPoliza, p.seguro)
                %s
                LEFT JOIN itemsCatalogo ic ON ic.producto = p.identificador
                LEFT JOIN catalogos c ON c.identificador = ic.catalogo
                LEFT JOIN subastas s ON s.identificador = c.subasta
                OUTER APPLY (
                    SELECT TOP 1 si.estado
                    FROM solicitudesIngreso si
                    WHERE si.duenio = p.duenio
                      AND si.descripcionBien = COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta)
                    ORDER BY si.fechaSolicitud DESC, si.identificador DESC
                ) last_request
                WHERE p.duenio = ?
                ORDER BY p.identificador DESC
                """.formatted(insurancePhoneSelect(), insurancePhoneJoin());
    }

    private String insurancePhoneSelect() {
        if (tableExists("seguros_contacto_ext")) {
            return "sec.nroTelefono";
        }

        if (columnExists("seguros_ext", "nroTelefono")) {
            return "se.nroTelefono";
        }

        return "NULL";
    }

    private String insurancePhoneJoin() {
        if (tableExists("seguros_contacto_ext")) {
            return "LEFT JOIN seguros_contacto_ext sec ON sec.nroPoliza = COALESCE(pe.nroPoliza, p.seguro)";
        }

        if (columnExists("seguros_ext", "nroTelefono")) {
            return "LEFT JOIN seguros_ext se ON se.nroPoliza = COALESCE(pe.nroPoliza, p.seguro)";
        }

        return "";
    }

    @Transactional
    public String registerOwnerProduct(
            Integer userId,
            String name,
            String condition,
            String category,
            String originProvenance,
            String fullDescription,
            Boolean ownershipDeclaration,
            Integer receivingAccountId,
            String preferredCurrency,
            Boolean acceptsUsd,
            Boolean isArt,
            String artist,
            LocalDate creationDate,
            String historicalContext,
            MultipartFile[] photos,
            MultipartFile[] originDocs
    ) {

        Integer authenticatedUserId = resolveAuthenticatedOwnerId(userId);


        if (isBlank(name)
                || isBlank(condition)
                || isBlank(category)
                || isBlank(originProvenance)
                || isBlank(fullDescription)
                || ownershipDeclaration == null
                || !ownershipDeclaration
                || receivingAccountId == null
                || receivingAccountId <= 0) {
            throw new OwnerProductValidationException("Invalid owner product request.");
        }


        validatePhotos(photos);
        ensureOwnerProfileExists(authenticatedUserId);

        BankAccountData receivingAccount = loadReceivingAccount(authenticatedUserId, receivingAccountId);
        ensureDestinationAccount(authenticatedUserId, receivingAccount);
        String normalizedPreferredCurrency = normalizeCurrency(preferredCurrency, null);
        String acceptsUsdFlag = toYesNo(Boolean.TRUE.equals(acceptsUsd)
                || "USD".equalsIgnoreCase(normalizedPreferredCurrency));
        validateReceivingAccountCurrency(normalizedPreferredCurrency, receivingAccount);

        Integer reviewerId = firstEmployeeId();
        Integer productId = insertProduct(authenticatedUserId, reviewerId, name, fullDescription);

        jdbcTemplate.update("""
                INSERT INTO productos_ext (identificador, deposito, nroPoliza)
                VALUES (?, NULL, NULL)
                """, productId);

        jdbcTemplate.update("""
                INSERT INTO productos_detalle (
                    identificador, titulo, descripcionLarga, artista,
                    fechaCreacion, historia, esObraDeArte,categoriaTematica
                )
                VALUES (?, ?, ?, ?, ?, ?, ?,?)
                """, productId, name, fullDescription, nullableTrim(artist), creationDate,
                buildHistory(originProvenance, historicalContext), normalizeArtFlag(isArt, name, fullDescription),category);

        for (MultipartFile photo : photos) {
            insertPhoto(productId, photo);
        }

        if (originDocs != null) {
            for (MultipartFile originDoc : originDocs) {
                if (originDoc != null && !originDoc.isEmpty()) {
                    // La base actual no tiene una tabla dedicada para documentos de origen.
                    break;
                }
            }
        }

        jdbcTemplate.update("""
                INSERT INTO solicitudesIngreso (
                    duenio, monedaPreferida, aceptaUsd, estado, fechaSolicitud, descripcionBien,
                    declaraPropiedad, declaraOrigenLicito, direccionEnvio,
                    motivoRechazo, revisadoPor, gastosDevolucion
                )
                VALUES (?, ?, ?, 'pendiente', GETDATE(), ?, 'si', 'si', NULL, NULL, NULL, NULL)
                """, authenticatedUserId, normalizedPreferredCurrency, acceptsUsdFlag, name);

        return "Tu artículo fue enviado para revisión. Quedó pendiente y sin póliza hasta que administración lo evalúe.";
    }

    public ProductPhotoBinary loadOwnerProductPhoto(Integer userId, Integer productId, Integer photoId) {
        Integer authenticatedUserId = resolveAuthenticatedOwnerId(userId);

        if (productId == null || productId <= 0 || photoId == null || photoId <= 0) {
            throw new OwnerProductValidationException("Invalid owner product photo request.");
        }

        try {
            byte[] bytes = jdbcTemplate.queryForObject("""
                    SELECT f.foto
                    FROM productos p
                    JOIN fotos f ON f.producto = p.identificador
                    WHERE p.identificador = ?
                      AND p.duenio = ?
                      AND f.identificador = ?
                    """, byte[].class, productId, authenticatedUserId, photoId);

            if (bytes == null || bytes.length == 0) {
                throw new OwnerProductValidationException("Invalid owner product photo request.");
            }

            return new ProductPhotoBinary(bytes, detectContentType(bytes));
        } catch (EmptyResultDataAccessException ex) {
            throw new OwnerProductValidationException("Invalid owner product photo request.");
        }
    }

    private Integer insertProduct(
            Integer ownerId,
            Integer reviewerId,
            String name,
            String fullDescription
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO productos (
                        fecha, disponible, descripcionCatalogo, descripcionCompleta,
                        revisor, duenio, seguro
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setDate(1, Date.valueOf(LocalDate.now()));
            ps.setString(2, "no");
            ps.setString(3, name);
            ps.setString(4, fullDescription);
            ps.setInt(5, reviewerId);
            ps.setInt(6, ownerId);
            ps.setNull(7, java.sql.Types.VARCHAR);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new OwnerProductValidationException("Invalid owner product request.");
        }
        return key.intValue();
    }

    private String firstOwnerProductPhotoUrl(Integer userId, Integer productId) {
        try {
            Integer photoId = jdbcTemplate.queryForObject("""
                    SELECT TOP 1 identificador
                    FROM fotos
                    WHERE producto = ?
                    ORDER BY identificador ASC
                    """, Integer.class, productId);

            if (photoId == null) {
                return null;
            }

            return "/api/v1/users/%d/products/%d/photos/%d".formatted(userId, productId, photoId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private void insertPhoto(Integer productId, MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            return;
        }

        try {
            jdbcTemplate.update("""
                    INSERT INTO fotos (producto, foto)
                    VALUES (?, ?)
                    """, productId, photo.getBytes());
        } catch (IOException ex) {
            throw new OwnerProductValidationException("Invalid owner product request.");
        }
    }

    private void validatePhotos(MultipartFile[] photos) {
        if (photos == null) {
            throw new InsufficientProductPhotosException("Missing product photos.");
        }

        long validPhotos = java.util.Arrays.stream(photos)
                .filter(photo -> photo != null && !photo.isEmpty())
                .count();

        if (validPhotos < 6) {
            throw new InsufficientProductPhotosException("Missing product photos.");
        }
    }

    private BankAccountData loadReceivingAccount(Integer userId, Integer paymentMethodId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        mdp.identificador AS payment_method_id,
                        mdp.moneda AS currency,
                        cb.banco AS bank_name,
                        cb.numeroCuenta AS account_number,
                        cb.pais AS country_id,
                        cb.cbu AS cbu,
                        cb.swift AS swift,
                        cb.iban AS iban
                    FROM mediosDePago mdp
                    JOIN cuentasBancarias cb ON cb.identificador = mdp.identificador
                    WHERE mdp.identificador = ?
                      AND mdp.cliente = ?
                      AND mdp.tipo = 'cuenta_bancaria'
                    """, (rs, rowNum) -> new BankAccountData(
                    rs.getInt("payment_method_id"),
                    rs.getString("currency"),
                    rs.getString("bank_name"),
                    rs.getString("account_number"),
                    rs.getInt("country_id"),
                    rs.getString("cbu"),
                    rs.getString("swift"),
                    rs.getString("iban")
            ), paymentMethodId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new OwnerProductValidationException("Invalid receiving account.");
        }
    }

    private void ensureDestinationAccount(Integer userId, BankAccountData account) {
        Integer ownerAccountId = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM cuentasDestinoVenta
                WHERE duenio = ?
                  AND numeroCuenta = ?
                """, Integer.class, userId, account.accountNumber());

        if (ownerAccountId != null && ownerAccountId > 0) {
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO cuentasDestinoVenta (
                    duenio, moneda, banco, numeroCuenta, pais,
                    cbu, swift, iban, estado
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'verificada')
                """, userId, account.currency(), account.bankName(), account.accountNumber(), account.countryId(),
                account.cbu(), account.swift(), account.iban());
    }

    private void validateReceivingAccountCurrency(String preferredCurrency, BankAccountData account) {
        if (preferredCurrency == null || preferredCurrency.isBlank()) {
            return;
        }

        String accountCurrency = normalizeCurrency(account.currency(), "ARS");
        if (!preferredCurrency.equalsIgnoreCase(accountCurrency)) {
            throw new OwnerProductValidationException(
                    "La cuenta elegida debe estar en " + preferredCurrency + " para enviar una solicitud con esa moneda."
            );
        }
    }

    private void ensureOwnerProfileExists(Integer userId) {
        if (ownerProfileExists(userId)) {
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO duenios (
                    identificador, numeroPais, verificacionFinanciera,
                    verificacionJudicial, calificacionRiesgo, verificador
                )
                VALUES (?, ?, 'no', 'no', 1, ?)
                """, userId, ownerCountryId(userId), firstEmployeeId());
    }

    private boolean ownerProfileExists(Integer userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM duenios
                WHERE identificador = ?
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    private Integer ownerCountryId(Integer userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT numeroPais
                    FROM clientes
                    WHERE identificador = ?
                    """, Integer.class, userId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private Integer firstEmployeeId() {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 identificador
                    FROM empleados
                    ORDER BY identificador ASC
                    """, Integer.class);
        } catch (EmptyResultDataAccessException ex) {
            throw new OwnerProductValidationException("Missing employee reviewer.");
        }
    }

    private Integer resolveAuthenticatedOwnerId(Integer requestedUserId) {
        if (requestedUserId == null || requestedUserId <= 0) {
            throw new UnauthorizedException("No autorizado.");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("No autorizado.");
        }

        UsuarioApp user = usuarioAppRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("No autorizado."));

        if (requestedUserId != null && !user.getIdentificador().equals(requestedUserId)) {
            throw new UnauthorizedException("No autorizado.");
        }

        return user.getIdentificador();
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME = ?
                    """, Integer.class, tableName);
            return count != null && count > 0;
        } catch (BadSqlGrammarException ex) {
            return false;
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_NAME = ?
                      AND COLUMN_NAME = ?
                    """, Integer.class, tableName, columnName);
            return count != null && count > 0;
        } catch (BadSqlGrammarException ex) {
            return false;
        }
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeCurrency(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ARS", "USD" -> normalized;
            default -> throw new OwnerProductValidationException("Moneda inválida. Debe ser ARS o USD.");
        };
    }

    private String toYesNo(boolean value) {
        return value ? "si" : "no";
    }

    private String nullableTrim(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildHistory(String originProvenance, String historicalContext) {
        String origin = nullableTrim(originProvenance);
        String context = nullableTrim(historicalContext);

        if (origin == null) {
            return context;
        }

        if (context == null) {
            return origin;
        }

        return origin + "\n\nContexto histórico: " + context;
    }

    private String normalizeArtFlag(Boolean isArt, String name, String fullDescription) {
        if (isArt != null) {
            return Boolean.TRUE.equals(isArt) ? "si" : "no";
        }

        return inferArtCategory(name, fullDescription);
    }

    private String detectContentType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "image/png";
        }

        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        if (bytes.length >= 6) {
            String header = new String(bytes, 0, 6);
            if ("GIF87a".equals(header) || "GIF89a".equals(header)) {
                return "image/gif";
            }
        }

        if (bytes.length >= 12) {
            String riff = new String(bytes, 0, 4);
            String webp = new String(bytes, 8, 4);
            if ("RIFF".equals(riff) && "WEBP".equals(webp)) {
                return "image/webp";
            }
        }

        return "application/octet-stream";
    }

    private String inferArtCategory(String name, String description) {
        String combined = ((name == null ? "" : name) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        return combined.contains("arte")
                || combined.contains("oleo")
                || combined.contains("óleo")
                || combined.contains("pintura")
                || combined.contains("escultura")
                ? "si"
                : "no";
    }

    private record BankAccountData(
            Integer paymentMethodId,
            String currency,
            String bankName,
            String accountNumber,
            Integer countryId,
            String cbu,
            String swift,
            String iban
    ) {
    }

    public record ProductPhotoBinary(byte[] bytes, String contentType) {
    }
}
