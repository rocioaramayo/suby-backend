package com.tpo.suby.service;

import com.tpo.suby.dto.request.ChangePasswordRequest;
import com.tpo.suby.dto.response.user.UserProfileResponse;
import com.tpo.suby.dto.response.user.CategoryProgressResponse;
import com.tpo.suby.dto.response.user.UserStatsResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UsuarioAppRepository usuarioAppRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final UserCategoryService userCategoryService;

    public void changePassword(Integer userId, ChangePasswordRequest request) {

        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InsufficientAuthenticationException(
                    "El enlace de configuracion de contrasena ha expirado o es invalido."
            );
        }

        String email = authentication.getName();

        UsuarioApp usuarioLogueado = usuarioAppRepository
                .findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));

        if (!usuarioLogueado.getIdentificador().equals(userId)) {
            throw new AccessDeniedException("No autorizado.");
        }

        UsuarioApp usuario = usuarioAppRepository
                .findById(userId)
                .orElseThrow(() ->
                        new NotFoundException("Usuario no encontrado.")
                );

        if (
                request.getPassword() == null ||
                request.getPasswordConfirmation() == null ||
                !request.getPassword().equals(request.getPasswordConfirmation()) ||
                request.getPassword().length() < 8
        ) {
            throw new RuntimeException(
                    "Errores de validacion: Las contrasenas no coinciden o no alcanzan el minimo de 8 caracteres."
            );
        }

        usuario.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        usuario.setUltimoLogin(LocalDateTime.now());
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);
                usuario.setTokenRecuperacion(null);
                usuario.setTokenExpira(null);

        usuarioAppRepository.save(usuario);
    }

    public UserProfileResponse getProfile(Integer userId) {
        UsuarioApp usuarioLogueado = authenticatedUser(
                userId,
                "No autorizado. Iniciá sesión para ver tu perfil.",
                "No tenés permiso para ver el perfil de otro usuario."
        );
        userCategoryService.refreshCategory(usuarioLogueado.getIdentificador());

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                        u.identificador AS id,
                        p.nombre AS name,
                        u.email AS email,
                        COALESCE(c.categoria, 'comun') AS category,
                        CASE WHEN COALESCE(c.admitido, 'no') = 'si' THEN 1 ELSE 0 END AS verified,
                        u.estadoApp AS account_status,
                        COALESCE(guarantee.declared_guarantee, 0) AS declared_guarantee,
                        COALESCE(wins.auctions_won, 0) AS auctions_won,
                        COALESCE(payments.distinct_payment_types, 0) AS distinct_payment_types
                    FROM usuarios_app u
                    JOIN personas p ON p.identificador = u.identificador
                    LEFT JOIN clientes c ON c.identificador = u.identificador
                    OUTER APPLY (
                        SELECT SUM(COALESCE(mdp.montoDisponible, 0)) AS declared_guarantee
                        FROM mediosDePago mdp
                        WHERE mdp.cliente = u.identificador
                    ) guarantee
                    OUTER APPLY (
                        SELECT COUNT(*) AS auctions_won
                        FROM pujos pu
                        JOIN asistentes a ON a.identificador = pu.asistente
                        WHERE a.cliente = u.identificador
                          AND pu.ganador = 'si'
                    ) wins
                    OUTER APPLY (
                        SELECT COUNT(DISTINCT mdp.tipo) AS distinct_payment_types
                        FROM mediosDePago mdp
                        WHERE mdp.cliente = u.identificador
                    ) payments
                    WHERE u.identificador = ?
                    """, (rs, rowNum) -> UserProfileResponse.builder()
                    .id(rs.getInt("id"))
                    .name(rs.getString("name"))
                    .email(rs.getString("email"))
                    .category(rs.getString("category"))
                    .verified(rs.getInt("verified") == 1)
                    .accountStatus(rs.getString("account_status"))
                    .declaredGuarantee(rs.getBigDecimal("declared_guarantee"))
                    .auctionsWon(rs.getInt("auctions_won"))
                    .distinctPaymentTypes(rs.getInt("distinct_payment_types"))
                    .build(), usuarioLogueado.getIdentificador());
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Usuario no encontrado.");
        }
    }

    public UserStatsResponse getStats(Integer userId) {
        UsuarioApp usuarioLogueado = authenticatedUser(
                userId,
                "No autorizado.",
                "No tenés permiso para ver las estadísticas de otro usuario."
        );
        userCategoryService.refreshCategory(usuarioLogueado.getIdentificador());

        try {
            StatsRow stats = jdbcTemplate.queryForObject("""
                    SELECT
                        COALESCE(c.categoria, 'comun') AS current_category,
                        COALESCE(bids.total_bids, 0) AS total_bids,
                        COALESCE(participation.total_auctions_participated, 0) AS total_auctions_participated,
                        COALESCE(wins.auctions_won, 0) AS auctions_won,
                        COALESCE(payments.payment_types_registered, 0) AS payment_types_registered
                    FROM usuarios_app u
                    LEFT JOIN clientes c ON c.identificador = u.identificador
                    OUTER APPLY (
                        SELECT COUNT(*) AS total_bids
                        FROM pujos pu
                        JOIN asistentes a ON a.identificador = pu.asistente
                        WHERE a.cliente = u.identificador
                    ) bids
                    OUTER APPLY (
                        SELECT COUNT(DISTINCT a.subasta) AS total_auctions_participated
                        FROM asistentes a
                        WHERE a.cliente = u.identificador
                    ) participation
                    OUTER APPLY (
                        SELECT COUNT(*) AS auctions_won
                        FROM pujos pu
                        JOIN asistentes a ON a.identificador = pu.asistente
                        WHERE a.cliente = u.identificador
                          AND pu.ganador = 'si'
                    ) wins
                    OUTER APPLY (
                        SELECT COUNT(DISTINCT mdp.tipo) AS payment_types_registered
                        FROM mediosDePago mdp
                        WHERE mdp.cliente = u.identificador
                    ) payments
                    WHERE u.identificador = ?
                    """, (rs, rowNum) -> new StatsRow(
                    rs.getString("current_category"),
                    rs.getInt("total_bids"),
                    rs.getInt("total_auctions_participated"),
                    rs.getInt("auctions_won"),
                    rs.getInt("payment_types_registered")
            ), usuarioLogueado.getIdentificador());

            CategoryRule nextRule = nextCategoryRule(stats.currentCategory());
            BigDecimal successRate = stats.totalBids() == 0
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(stats.auctionsWon())
                    .divide(BigDecimal.valueOf(stats.totalBids()), 2, RoundingMode.HALF_UP);

            return UserStatsResponse.builder()
                    .successRate(successRate)
                    .totalBids(stats.totalBids())
                    .totalAuctionsParticipated(stats.totalAuctionsParticipated())
                    .auctionsWon(stats.auctionsWon())
                    .categoryProgress(CategoryProgressResponse.builder()
                            .currentCategory(stats.currentCategory())
                            .nextCategory(nextRule.nextCategory())
                            .auctionsWon(stats.auctionsWon())
                            .auctionsRequired(nextRule.auctionsRequired())
                            .paymentTypesRegistered(stats.paymentTypesRegistered())
                            .paymentTypesRequired(nextRule.paymentTypesRequired())
                            .build())
                    .build();
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Usuario no encontrado.");
        }
    }

    private UsuarioApp authenticatedUser(Integer userId, String unauthorizedMessage, String forbiddenMessage) {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new InsufficientAuthenticationException(unauthorizedMessage);
        }

        String email = authentication.getName();

        UsuarioApp usuarioLogueado = usuarioAppRepository
                .findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));

        if (!usuarioLogueado.getIdentificador().equals(userId)) {
            throw new AccessDeniedException(forbiddenMessage);
        }

        return usuarioLogueado;
    }

    private CategoryRule nextCategoryRule(String currentCategory) {
        return switch (normalizeCategory(currentCategory)) {
            case "especial" -> new CategoryRule("plata", 3, 1);
            case "plata" -> new CategoryRule("oro", 5, 2);
            case "oro" -> new CategoryRule("platino", 10, 3);
            case "platino" -> new CategoryRule("platino", 10, 3);
            default -> new CategoryRule("especial", 1, 1);
        };
    }

    private String normalizeCategory(String category) {
        return category == null ? "comun" : category.trim().toLowerCase();
    }

    private record StatsRow(
            String currentCategory,
            Integer totalBids,
            Integer totalAuctionsParticipated,
            Integer auctionsWon,
            Integer paymentTypesRegistered
    ) {
    }

    private record CategoryRule(
            String nextCategory,
            Integer auctionsRequired,
            Integer paymentTypesRequired
    ) {
    }
}
