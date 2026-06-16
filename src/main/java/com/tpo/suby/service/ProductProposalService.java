package com.tpo.suby.service;

import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.OwnerProductValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductProposalService {

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;
    private final PrivateMessageService privateMessageService;

    @Transactional
    public String acceptProposal(Integer userId, Integer notificationId) {
        validateOwner(userId);

        ProposalContext context = loadProposalContext(userId, notificationId);
        if (!context.isProposal()) {
            throw new OwnerProductValidationException("La notificación no corresponde a una propuesta.");
        }

        jdbcTemplate.update("""
                UPDATE solicitudesIngreso
                SET estado = 'aceptado'
                WHERE identificador = ?
                """, context.requestId());

        privateMessageService.createPrivateMessage(
                userId,
                "aviso_general",
                "Aceptaste la propuesta",
                "Aceptaste el valor base propuesto. Ahora el equipo puede incluir tu lote en una subasta.",
                Map.of(
                        "product_id", String.valueOf(context.productId()),
                        "product_name", context.productName(),
                        "base_price", context.basePrice().toPlainString(),
                        "proposal_message_id", String.valueOf(notificationId),
                        "cta_label", "Ver mis bienes",
                        "cta_target", "/profile"
                )
        );

        return "Aceptaste la propuesta correctamente.";
    }

    @Transactional
    public String rejectProposal(Integer userId, Integer notificationId) {
        validateOwner(userId);

        ProposalContext context = loadProposalContext(userId, notificationId);
        if (!context.isProposal()) {
            throw new OwnerProductValidationException("La notificación no corresponde a una propuesta.");
        }

        jdbcTemplate.update("""
                UPDATE solicitudesIngreso
                SET estado = 'propuesto_rechazado'
                WHERE identificador = ?
                """, context.requestId());

        privateMessageService.createPrivateMessage(
                userId,
                "aviso_general",
                "Rechazaste la propuesta",
                "Rechazaste el valor base propuesto. El equipo podrá revisar el bien y generar una nueva propuesta.",
                Map.of(
                        "product_id", String.valueOf(context.productId()),
                        "product_name", context.productName(),
                        "proposal_message_id", String.valueOf(notificationId),
                        "cta_label", "Volver a mensajes",
                        "cta_target", "/notifications"
                )
        );

        return "Rechazaste la propuesta correctamente.";
    }

    private ProposalContext loadProposalContext(Integer userId, Integer messageId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        mp.identificador AS message_id,
                        mp.tipo AS message_type,
                        mp.destinatario AS recipient_id,
                        COALESCE(fk.valor, '') AS flow_kind,
                        TRY_CAST(pid.valor AS INT) AS product_id,
                        COALESCE(pn.valor, p.descripcionCatalogo, p.descripcionCompleta) AS product_name,
                        TRY_CAST(bp.valor AS DECIMAL(18, 2)) AS base_price,
                        si.identificador AS request_id
                    FROM mensajes_privados mp
                    JOIN mensajes_datos pid ON pid.mensaje = mp.identificador AND pid.clave = 'product_id'
                    LEFT JOIN mensajes_datos fk ON fk.mensaje = mp.identificador AND fk.clave = 'flow_kind'
                    LEFT JOIN mensajes_datos pn ON pn.mensaje = mp.identificador AND pn.clave = 'product_name'
                    LEFT JOIN mensajes_datos bp ON bp.mensaje = mp.identificador AND bp.clave = 'base_price'
                    LEFT JOIN productos p ON p.identificador = TRY_CAST(pid.valor AS INT)
                    LEFT JOIN solicitudesIngreso si
                        ON si.duenio = mp.destinatario
                       AND si.descripcionBien = COALESCE(
                            pn.valor,
                            p.descripcionCatalogo,
                            p.descripcionCompleta
                        )
                    WHERE mp.identificador = ?
                      AND mp.destinatario = ?
                      AND (mp.tipo = 'propuesta_precio' OR (mp.tipo = 'aviso_general' AND fk.valor = 'proposal_price'))
                    ORDER BY mp.enviadoEn DESC, mp.identificador DESC
                    """, (rs, rowNum) -> new ProposalContext(
                    rs.getInt("message_id"),
                    rs.getString("message_type"),
                    rs.getInt("recipient_id"),
                    rs.getString("flow_kind"),
                    rs.getInt("product_id"),
                    rs.getString("product_name"),
                    rs.getBigDecimal("base_price"),
                    rs.getInt("request_id")
            ), messageId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Propuesta no encontrada.");
        }
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

    private record ProposalContext(
            Integer messageId,
            String messageType,
            Integer recipientId,
            String flowKind,
            Integer productId,
            String productName,
            BigDecimal basePrice,
            Integer requestId
    ) {
        private boolean isProposal() {
            return "propuesta_precio".equalsIgnoreCase(messageType)
                    || "proposal_price".equalsIgnoreCase(flowKind);
        }
    }
}
