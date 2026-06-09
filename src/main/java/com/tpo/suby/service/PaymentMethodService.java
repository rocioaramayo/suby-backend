package com.tpo.suby.service;

import com.tpo.suby.dto.request.payment.PaymentMethodRequest;
import com.tpo.suby.dto.response.payment.CreatedPaymentMethodResponse;
import com.tpo.suby.dto.response.payment.PaymentMethodItemResponse;
import com.tpo.suby.dto.response.payment.PaymentMethodsResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.DuplicatePaymentMethodException;
import com.tpo.suby.exception.PaymentMethodValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PaymentMethodService {

    private static final DateTimeFormatter CARD_EXPIRY_FORMAT = DateTimeFormatter.ofPattern("MM/yy");

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final UsuarioAppRepository usuarioAppRepository;
    private final UserCategoryService userCategoryService;

    public PaymentMethodsResponse listPaymentMethods(Integer userId) {
        validateOwner(userId);

        List<PaymentMethodItemResponse> paymentMethods = jdbcTemplate.query("""
                SELECT
                    mdp.identificador AS id,
                    mdp.tipo AS db_type,
                    mdp.montoDisponible AS available_balance,
                    mdp.montoUsado AS used_balance,
                    tc.numeroEnmascarado,
                    tc.redTarjeta,
                    cb.banco AS cuenta_banco,
                    cb.numeroCuenta,
                    cc.banco AS cheque_banco
                FROM mediosDePago mdp
                LEFT JOIN tarjetasCredito tc ON tc.identificador = mdp.identificador
                LEFT JOIN cuentasBancarias cb ON cb.identificador = mdp.identificador
                LEFT JOIN chequesCertificados cc ON cc.identificador = mdp.identificador
                WHERE mdp.cliente = ?
                ORDER BY mdp.identificador ASC
                """, (rs, rowNum) -> {
            String dbType = rs.getString("db_type");
            BigDecimal available = rs.getBigDecimal("available_balance");
            BigDecimal used = rs.getBigDecimal("used_balance");
            if (available == null) {
                available = BigDecimal.ZERO;
            }
            if (used == null) {
                used = BigDecimal.ZERO;
            }

            return PaymentMethodItemResponse.builder()
                    .id(rs.getInt("id"))
                    .type(toApiType(dbType))
                    .label(buildLabel(
                            dbType,
                            rs.getString("redTarjeta"),
                            rs.getString("numeroEnmascarado"),
                            rs.getString("cuenta_banco"),
                            rs.getString("numeroCuenta"),
                            rs.getString("cheque_banco")
                    ))
                    .availableBalance(available.subtract(used))
                    .build();
        }, userId);

        return PaymentMethodsResponse.builder()
                .paymentMethods(paymentMethods)
                .build();
    }

    @Transactional
    public CreatedPaymentMethodResponse addPaymentMethod(Integer userId, PaymentMethodRequest request) {
        validateOwner(userId);

        if (request == null || isBlank(request.getType())) {
            throw new PaymentMethodValidationException("Invalid payment method.");
        }

        String type = normalize(request.getType());
        return switch (type) {
            case "tarjeta" -> addCard(userId, request);
            case "cuenta_bancaria" -> addBankAccount(userId, request);
            case "cheque" -> addCertifiedCheck(userId, request);
            default -> throw new PaymentMethodValidationException("Invalid payment method.");
        };
    }

    private CreatedPaymentMethodResponse addCard(Integer userId, PaymentMethodRequest request) {
        if (!isValidCard(request)) {
            throw new PaymentMethodValidationException("Invalid card.");
        }

        String masked = maskLast4(request.getCardNumber());
        if (exists("""
                SELECT COUNT(*)
                FROM mediosDePago mdp
                JOIN tarjetasCredito tc ON tc.identificador = mdp.identificador
                WHERE mdp.cliente = ?
                  AND tc.numeroEnmascarado = ?
                  AND LOWER(tc.nombreTitular) = ?
                """, userId, masked, normalize(request.getCardHolder()))) {
            throw new DuplicatePaymentMethodException("Duplicate card.");
        }

        Integer paymentMethodId = insertPaymentMethod(
                userId,
                "tarjeta_credito",
                "verificado",
                normalizeCurrency(request.getCurrency(), "ARS"),
                BigDecimal.ZERO,
                cardExpirationDate(request.getExpiry())
        );

        String brand = detectCardBrand(request.getCardNumber());
        String isInternational = isInternationalCard(request) ? "si" : "no";
        jdbcTemplate.update("""
                INSERT INTO tarjetasCredito (
                    identificador, nombreTitular, numeroEnmascarado,
                    redTarjeta, esInternacional, pais
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """, paymentMethodId, request.getCardHolder(), masked, brand, isInternational, cardCountryId(request));

        CreatedPaymentMethodResponse response = CreatedPaymentMethodResponse.builder()
                .paymentMethodId(paymentMethodId)
                .type("tarjeta")
                .label(cardLabel(brand, masked))
                .build();
        userCategoryService.refreshCategory(userId);
        return response;
    }

    private CreatedPaymentMethodResponse addBankAccount(Integer userId, PaymentMethodRequest request) {
        if (!isValidBankAccount(request)) {
            throw new PaymentMethodValidationException("Invalid bank account.");
        }

        if (exists("""
                SELECT COUNT(*)
                FROM mediosDePago mdp
                JOIN cuentasBancarias cb ON cb.identificador = mdp.identificador
                WHERE mdp.cliente = ?
                  AND cb.numeroCuenta = ?
                """, userId, request.getAccountNumber())) {
            throw new DuplicatePaymentMethodException("Duplicate bank account.");
        }

        Integer paymentMethodId = insertPaymentMethod(
                userId,
                "cuenta_bancaria",
                "verificado",
                normalizeCurrency(request.getCurrency(), defaultCurrencyForBank(request)),
                positiveOrZero(request.getReservedAmount()),
                null
        );

        Integer countryId = countryId(request.getCountry());
        String cbu = isArgentina(request.getCountry()) ? request.getCbuIban() : null;
        String iban = isArgentina(request.getCountry()) ? null : request.getCbuIban();

        jdbcTemplate.update("""
                INSERT INTO cuentasBancarias (
                    identificador, banco, numeroCuenta, tipoCuenta,
                    pais, cbu, swift, iban
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, paymentMethodId, request.getBankName(), request.getAccountNumber(),
                isArgentina(request.getCountry()) ? "corriente" : "extranjera",
                countryId, cbu, null, iban);

        CreatedPaymentMethodResponse response = CreatedPaymentMethodResponse.builder()
                .paymentMethodId(paymentMethodId)
                .type("cuenta_bancaria")
                .label("%s - Cta. %s".formatted(request.getBankName(), maskLast4(request.getAccountNumber())))
                .build();
        userCategoryService.refreshCategory(userId);
        return response;
    }

    private CreatedPaymentMethodResponse addCertifiedCheck(Integer userId, PaymentMethodRequest request) {
        if (!isValidCheck(request)) {
            throw new PaymentMethodValidationException("Invalid certified check.");
        }

        if (exists("""
                SELECT COUNT(*)
                FROM mediosDePago mdp
                JOIN chequesCertificados cc ON cc.identificador = mdp.identificador
                WHERE mdp.cliente = ?
                  AND cc.numeroCheque = ?
                  AND LOWER(cc.banco) = ?
                """, userId, request.getCheckNumber(), normalize(request.getBankName()))) {
            throw new DuplicatePaymentMethodException("Duplicate check.");
        }

        Integer employeeId = firstEmployeeId();
        Integer auctionId = resolveCertifiedCheckAuctionId(request.getAuctionId());
        Integer paymentMethodId = insertPaymentMethod(
                userId,
                "cheque_certificado",
                "pendiente",
                normalizeCurrency(request.getCurrency(), "ARS"),
                request.getAmount(),
                null
        );

        jdbcTemplate.update("""
                INSERT INTO chequesCertificados (
                    identificador, banco, numeroCheque, montoGarantia,
                    fechaEntrega, verificadoPor, subasta
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, paymentMethodId, request.getBankName(), request.getCheckNumber(),
                request.getAmount(), Date.valueOf(request.getIssueDate()), employeeId, auctionId);

        CreatedPaymentMethodResponse response = CreatedPaymentMethodResponse.builder()
                .paymentMethodId(paymentMethodId)
                .type("cheque")
                .label("Cheque certificado - %s".formatted(request.getBankName()))
                .build();
        userCategoryService.refreshCategory(userId);
        return response;
    }

    private Integer insertPaymentMethod(
            Integer userId,
            String dbType,
            String status,
            String currency,
            BigDecimal availableAmount,
            LocalDate expirationDate
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO mediosDePago (
                        cliente, tipo, estado, moneda,
                        montoDisponible, montoUsado, fechaVencimiento
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, userId);
            ps.setString(2, dbType);
            ps.setString(3, status);
            ps.setString(4, currency);
            ps.setBigDecimal(5, availableAmount);
            ps.setBigDecimal(6, BigDecimal.ZERO);
            if (expirationDate == null) {
                ps.setNull(7, java.sql.Types.DATE);
            } else {
                ps.setDate(7, Date.valueOf(expirationDate));
            }
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new PaymentMethodValidationException("Invalid payment method.");
        }
        return key.intValue();
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

        if (!exists("SELECT COUNT(*) FROM clientes WHERE identificador = ?", userId)) {
            throw new PaymentMethodValidationException("Invalid client.");
        }
    }

    private boolean isValidCard(PaymentMethodRequest request) {
        return onlyDigits(request.getCardNumber())
                && request.getCardNumber().length() >= 13
                && request.getCardNumber().length() <= 19
                && !isBlank(request.getCardHolder())
                && isValidExpiry(request.getExpiry())
                && onlyDigits(request.getCvv())
                && request.getCvv().length() >= 3
                && request.getCvv().length() <= 4;
    }

    private boolean isValidBankAccount(PaymentMethodRequest request) {
        return !isBlank(request.getBankName())
                && !isBlank(request.getCountry())
                && !isBlank(request.getAccountNumber())
                && !isBlank(request.getCbuIban())
                && !isBlank(request.getAccountHolder())
                && request.getReservedAmount() != null
                && request.getReservedAmount().compareTo(BigDecimal.ZERO) >= 0;
    }

    private boolean isValidCheck(PaymentMethodRequest request) {
        return !isBlank(request.getBankName())
                && !isBlank(request.getCheckNumber())
                && request.getAmount() != null
                && request.getAmount().compareTo(BigDecimal.ZERO) > 0
                && request.getIssueDate() != null
                && !isBlank(request.getHolderName());
    }

    private Integer cardCountryId(PaymentMethodRequest request) {
        if (isBlank(request.getCountry())) {
            return defaultCountryId();
        }
        return countryId(request.getCountry());
    }

    private boolean isInternationalCard(PaymentMethodRequest request) {
        if (!isBlank(request.getCountry()) && !isArgentina(request.getCountry())) {
            return true;
        }
        return "USD".equals(normalizeCurrency(request.getCurrency(), "ARS"));
    }

    private String defaultCurrencyForBank(PaymentMethodRequest request) {
        if (Boolean.TRUE.equals(request.getForeignBank())) {
            return "USD";
        }
        if (!isBlank(request.getCountry()) && !isArgentina(request.getCountry())) {
            return "USD";
        }
        return "ARS";
    }

    private Integer defaultCountryId() {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 numero
                    FROM paises
                    WHERE LOWER(nombre) = 'argentina' OR LOWER(nombreCorto) = 'ar'
                    ORDER BY numero ASC
                    """, Integer.class);
        } catch (EmptyResultDataAccessException ex) {
            try {
                return jdbcTemplate.queryForObject("""
                        SELECT TOP 1 numero
                        FROM paises
                        WHERE LOWER(nombre) LIKE '%argentina%'
                           OR LOWER(nombreCorto) LIKE '%ar%'
                        ORDER BY numero ASC
                        """, Integer.class);
            } catch (EmptyResultDataAccessException ignored) {
                return jdbcTemplate.queryForObject("SELECT TOP 1 numero FROM paises ORDER BY numero ASC", Integer.class);
            }
        }
    }

    private Integer countryId(String country) {
        String normalizedCountry = normalize(country);

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 numero
                    FROM paises
                    WHERE LOWER(nombre) = ? OR LOWER(nombreCorto) = ?
                    ORDER BY numero ASC
                    """, Integer.class, normalizedCountry, normalizedCountry);
        } catch (EmptyResultDataAccessException ex) {
            try {
                return jdbcTemplate.queryForObject("""
                        SELECT TOP 1 numero
                        FROM paises
                        WHERE LOWER(nombre) LIKE ?
                           OR LOWER(nombreCorto) LIKE ?
                        ORDER BY numero ASC
                        """, Integer.class, "%" + normalizedCountry + "%", "%" + normalizedCountry + "%");
            } catch (EmptyResultDataAccessException ignored) {
                throw new PaymentMethodValidationException("Invalid country.");
            }
        }
    }

    private Integer firstEmployeeId() {
        try {
            return jdbcTemplate.queryForObject("SELECT TOP 1 identificador FROM empleados ORDER BY identificador ASC", Integer.class);
        } catch (EmptyResultDataAccessException ex) {
            throw new PaymentMethodValidationException("Missing employee verifier.");
        }
    }

    private Integer resolveCertifiedCheckAuctionId(Integer requestedAuctionId) {
        if (requestedAuctionId != null && requestedAuctionId > 0) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM subastas
                    WHERE identificador = ?
                    """, Integer.class, requestedAuctionId);
            if (count != null && count > 0) {
                return requestedAuctionId;
            }
            throw new PaymentMethodValidationException("Invalid auction for certified check.");
        }

        List<Integer> candidateAuctions = jdbcTemplate.query("""
                SELECT s.identificador
                FROM subastas s
                WHERE s.estado = 'abierta'
                  AND (
                        CAST(s.fecha AS DATE) > CAST(GETDATE() AS DATE)
                        OR (
                            CAST(s.fecha AS DATE) = CAST(GETDATE() AS DATE)
                            AND CAST(s.hora AS TIME) >= CAST(GETDATE() AS TIME)
                        )
                  )
                ORDER BY s.fecha ASC, s.hora ASC
                """, (rs, rowNum) -> rs.getInt("identificador"));

        if (candidateAuctions.size() == 1) {
            return candidateAuctions.get(0);
        }
        if (candidateAuctions.isEmpty()) {
            throw new PaymentMethodValidationException("Missing auction for certified check.");
        }
        throw new PaymentMethodValidationException("Certified check must be linked to a specific auction.");
    }

    private String normalizeCurrency(String currency, String defaultCurrency) {
        if (isBlank(currency)) {
            return defaultCurrency;
        }

        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (!"ARS".equals(normalized) && !"USD".equals(normalized)) {
            throw new PaymentMethodValidationException("Invalid currency.");
        }
        return normalized;
    }

    private Integer firstAuctionId() {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 identificador
                    FROM subastas
                    ORDER BY
                        CASE WHEN estado = 'abierta' THEN 0 ELSE 1 END,
                        fecha ASC,
                        hora ASC
                    """, Integer.class);
        } catch (EmptyResultDataAccessException ex) {
            throw new PaymentMethodValidationException("Missing auction for certified check.");
        }
    }

    private boolean exists(String sql, Object... params) {
        Integer count = jdbcClient.sql(sql)
                .params(params)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private String toApiType(String dbType) {
        return switch (dbType) {
            case "tarjeta_credito" -> "tarjeta";
            case "cheque_certificado" -> "cheque";
            default -> dbType;
        };
    }

    private String buildLabel(String dbType, String cardBrand, String maskedCard, String bankName, String accountNumber, String checkBank) {
        return switch (dbType) {
            case "tarjeta_credito" -> cardLabel(cardBrand, maskedCard);
            case "cuenta_bancaria" -> "%s - Cta. %s".formatted(bankName, maskLast4(accountNumber));
            case "cheque_certificado" -> "Cheque certificado - %s".formatted(checkBank == null ? "Pendiente" : checkBank);
            default -> "Medio de pago";
        };
    }

    private String cardLabel(String brand, String masked) {
        return "%s %s".formatted(capitalize(brand), masked);
    }

    private String detectCardBrand(String cardNumber) {
        if (cardNumber.startsWith("4")) {
            return "visa";
        }
        if (cardNumber.startsWith("5")) {
            return "mastercard";
        }
        if (cardNumber.startsWith("34") || cardNumber.startsWith("37")) {
            return "amex";
        }
        return "otra";
    }

    private LocalDate cardExpirationDate(String expiry) {
        YearMonth yearMonth = YearMonth.parse(expiry, CARD_EXPIRY_FORMAT);
        return yearMonth.atEndOfMonth();
    }

    private boolean isValidExpiry(String expiry) {
        if (isBlank(expiry)) {
            return false;
        }
        try {
            return !YearMonth.parse(expiry, CARD_EXPIRY_FORMAT).isBefore(YearMonth.now());
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private BigDecimal positiveOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isArgentina(String country) {
        String normalized = normalize(country);
        return "argentina".equals(normalized) || "ar".equals(normalized);
    }

    private boolean onlyDigits(String value) {
        return value != null && value.chars().allMatch(Character::isDigit);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String maskLast4(String value) {
        if (value == null || value.length() < 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        return normalized.toLowerCase(Locale.ROOT);
    }

    private String capitalize(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }
}
