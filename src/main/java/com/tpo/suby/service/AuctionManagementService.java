package com.tpo.suby.service;

import com.tpo.suby.dto.request.admin.CreateAuctionRequest;
import com.tpo.suby.dto.request.admin.CreateAuctionLotRequest;
import com.tpo.suby.dto.request.admin.CreateProductInsuranceRequest;
import com.tpo.suby.dto.request.admin.ProposeProductRequest;
import com.tpo.suby.dto.request.admin.RejectProductRequest;
import com.tpo.suby.dto.request.admin.AssignProductInsuranceRequest;
import com.tpo.suby.dto.response.admin.AdminInsuranceOptionResponse;
import com.tpo.suby.dto.response.admin.AdminAuctionCreationResponse;
import com.tpo.suby.dto.response.admin.AdminProductInsuranceOptionsResponse;
import com.tpo.suby.dto.response.admin.AdminProductReviewItemResponse;
import com.tpo.suby.dto.response.admin.AdminProductReviewResponse;
import com.tpo.suby.dto.response.admin.AdminSubastadorOptionResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.OwnerProductValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
public class AuctionManagementService {

    private static final String ADMIN_EMAIL = "admin@suby.com";

    private final JdbcTemplate jdbcTemplate;
    private final UsuarioAppRepository usuarioAppRepository;
    private final PrivateMessageService privateMessageService;

    public AdminProductReviewResponse listReviewQueue() {
        validateAdminAccess();

        List<AdminProductReviewItemResponse> products = jdbcTemplate.query("""
                SELECT
                    p.identificador AS product_id,
                    p.duenio AS owner_id,
                    owner.nombre AS owner_name,
                    COALESCE(pd.titulo, NULLIF(p.descripcionCatalogo, 'No Posee'), p.descripcionCompleta) AS title,
                    CASE
                        WHEN LOWER(COALESCE(pd.esObraDeArte, 'no')) = 'si' THEN 'arte'
                        WHEN s.identificador IS NOT NULL THEN s.categoria
                        ELSE 'general'
                    END AS category,
                    CASE
                        WHEN last_request.estado IN ('aceptado', 'rechazado') THEN last_request.estado
                        WHEN proposal.proposal_message_id IS NOT NULL THEN 'propuesto'
                        ELSE COALESCE(last_request.estado, 'pendiente')
                    END AS inspection_status,
                    COALESCE(photo_count.total_photos, 0) AS photo_count,
                    COALESCE(ic.precioBase, seg.importe, 0) AS estimated_value,
                    ic.precioBase AS published_base_price,
                    proposal.proposal_message_id AS proposal_message_id,
                    proposal.proposed_base_price AS proposed_base_price,
                    thumbnail.photo_id AS thumbnail_photo_id,
                    last_request.fechaSolicitud AS request_date,
                    s.identificador AS auction_id
                FROM productos p
                JOIN duenios d ON d.identificador = p.duenio
                JOIN personas owner ON owner.identificador = d.identificador
                LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                LEFT JOIN itemsCatalogo ic ON ic.producto = p.identificador
                LEFT JOIN catalogos c ON c.identificador = ic.catalogo
                LEFT JOIN subastas s ON s.identificador = c.subasta
                LEFT JOIN seguros seg ON seg.nroPoliza = COALESCE(p.seguro, '')
                OUTER APPLY (
                    SELECT COUNT(*) AS total_photos
                    FROM fotos f
                    WHERE f.producto = p.identificador
                ) photo_count
                OUTER APPLY (
                    SELECT TOP 1
                        f.identificador AS photo_id
                    FROM fotos f
                    WHERE f.producto = p.identificador
                    ORDER BY f.identificador ASC
                ) thumbnail
                OUTER APPLY (
                    SELECT TOP 1
                        si.identificador,
                        si.estado,
                        si.fechaSolicitud
                    FROM solicitudesIngreso si
                    WHERE si.duenio = p.duenio
                      AND si.descripcionBien = COALESCE(pd.titulo, NULLIF(p.descripcionCatalogo, 'No Posee'), p.descripcionCompleta)
                    ORDER BY si.fechaSolicitud DESC, si.identificador DESC
                ) last_request
                OUTER APPLY (
                    SELECT TOP 1
                        mp.identificador AS proposal_message_id,
                        TRY_CAST(bp.valor AS DECIMAL(18, 2)) AS proposed_base_price
                    FROM mensajes_privados mp
                    JOIN mensajes_datos pid ON pid.mensaje = mp.identificador AND pid.clave = 'product_id'
                    LEFT JOIN mensajes_datos fk ON fk.mensaje = mp.identificador AND fk.clave = 'flow_kind'
                    LEFT JOIN mensajes_datos bp ON bp.mensaje = mp.identificador AND bp.clave = 'base_price'
                    WHERE mp.destinatario = p.duenio
                      AND (
                            mp.tipo = 'propuesta_precio'
                            OR (mp.tipo = 'aviso_general' AND fk.valor = 'proposal_price')
                      )
                      AND pid.valor = CAST(p.identificador AS VARCHAR(20))
                    ORDER BY mp.enviadoEn DESC, mp.identificador DESC
                ) proposal
                ORDER BY p.identificador DESC
                """, (rs, rowNum) -> {
            String inspectionStatus = rs.getString("inspection_status");
            Integer photoCount = rs.getInt("photo_count");
            Integer auctionId = nullableInteger(rs, "auction_id");
            boolean canCreateAuction = "aceptado".equalsIgnoreCase(inspectionStatus)
                    && auctionId == null
                    && photoCount >= 6;

            return AdminProductReviewItemResponse.builder()
                    .productId(rs.getInt("product_id"))
                    .ownerId(rs.getInt("owner_id"))
                    .ownerName(rs.getString("owner_name"))
                    .title(rs.getString("title"))
                    .category(rs.getString("category"))
                    .inspectionStatus(inspectionStatus)
                    .photoCount(photoCount)
                    .estimatedValue(rs.getBigDecimal("estimated_value"))
                    .publishedBasePrice(rs.getBigDecimal("published_base_price"))
                    .proposedBasePrice(rs.getBigDecimal("proposed_base_price"))
                    .proposalMessageId(nullableInteger(rs, "proposal_message_id"))
                    .thumbnailUrl(buildItemPhotoUrl(rs.getInt("product_id"), nullableInteger(rs, "thumbnail_photo_id")))
                    .requestDate(toLocalDate(rs.getDate("request_date")))
                    .auctionId(auctionId)
                    .canCreateAuction(canCreateAuction)
                    .build();
        });

        int accepted = (int) products.stream().filter(product -> "aceptado".equalsIgnoreCase(product.getInspectionStatus())).count();
        int proposed = (int) products.stream().filter(product -> "propuesto".equalsIgnoreCase(product.getInspectionStatus())).count();
        int rejected = (int) products.stream().filter(product -> "rechazado".equalsIgnoreCase(product.getInspectionStatus())).count();
        int pending = (int) products.stream().filter(product -> "pendiente".equalsIgnoreCase(product.getInspectionStatus())
                || "en_revision".equalsIgnoreCase(product.getInspectionStatus())).count();

        return AdminProductReviewResponse.builder()
                .products(products)
                .total(products.size())
                .accepted(accepted)
                .proposed(proposed)
                .pending(pending)
                .rejected(rejected)
                .build();
    }

    public List<AdminSubastadorOptionResponse> listSubastadores() {
        validateAdminAccess();

        return jdbcTemplate.query("""
                SELECT
                    s.identificador AS id,
                    p.nombre AS name,
                    s.matricula AS license
                FROM subastadores s
                JOIN personas p ON p.identificador = s.identificador
                ORDER BY p.nombre ASC
                """, (rs, rowNum) -> AdminSubastadorOptionResponse.builder()
                .id(rs.getInt("id"))
                .name(rs.getString("name"))
                .license(rs.getString("license"))
                .build());
    }

    public AdminProductInsuranceOptionsResponse listProductInsuranceOptions(Integer productId) {
        validateAdminAccess();
        ProductContext context = loadProductContext(productId);
        ensureApprovedProductForInsurance(context);

        List<AdminInsuranceOptionResponse> options = jdbcTemplate.query("""
                SELECT
                    s.nroPoliza AS insurance_policy,
                    s.compania AS company,
                    s.polizaCombinada AS combined_flag,
                    s.importe AS amount,
                    %s AS insurer_phone,
                    (
                        SELECT COUNT(*)
                        FROM productos p2
                        WHERE p2.seguro = s.nroPoliza
                    ) AS assigned_products
                FROM seguros s
                JOIN seguros_ext se ON se.nroPoliza = s.nroPoliza
                %s
                WHERE se.duenio = ?
                  AND (
                        s.polizaCombinada = 'si'
                        OR NOT EXISTS (
                            SELECT 1
                            FROM productos p3
                            WHERE p3.seguro = s.nroPoliza
                              AND p3.identificador <> ?
                        )
                      )
                ORDER BY s.compania ASC, s.nroPoliza ASC
                """.formatted(insurerPhoneSelect("se", "sec"), insurerPhoneJoin("s")),
                (rs, rowNum) -> AdminInsuranceOptionResponse.builder()
                        .insurancePolicy(rs.getString("insurance_policy"))
                        .company(rs.getString("company"))
                        .combinedPolicy("si".equalsIgnoreCase(rs.getString("combined_flag")))
                        .amount(rs.getBigDecimal("amount"))
                        .insurerPhone(rs.getString("insurer_phone"))
                        .assignedProducts(rs.getInt("assigned_products"))
                        .currentForProduct(rs.getString("insurance_policy") != null
                                && rs.getString("insurance_policy").equalsIgnoreCase(context.currentInsurancePolicy()))
                        .build(),
                context.ownerId(), context.productId());

        return AdminProductInsuranceOptionsResponse.builder()
                .productId(context.productId())
                .ownerId(context.ownerId())
                .ownerName(context.ownerName())
                .currentInsurancePolicy(context.currentInsurancePolicy())
                .options(options)
                .build();
    }

    @Transactional
    public String proposeProduct(Integer productId, ProposeProductRequest request) {
        Integer employeeId = resolveOperatorId();
        ProductContext context = loadProductContext(productId);

        if (context.requestId() == null) {
            throw new OwnerProductValidationException("No encontramos una solicitud para este producto.");
        }

        BigDecimal basePrice = request != null && request.getBasePrice() != null && request.getBasePrice().compareTo(BigDecimal.ZERO) > 0
                ? request.getBasePrice()
                : context.estimatedValue();
        BigDecimal commissionPct = request != null && request.getCommissionPct() != null && request.getCommissionPct().compareTo(BigDecimal.ZERO) > 0
                ? request.getCommissionPct()
                : new BigDecimal("12.50");
        String note = request != null && !isBlank(request.getNote())
                ? request.getNote().trim()
                : "Valor base propuesto por el panel de Suby.";

        jdbcTemplate.update("""
                UPDATE solicitudesIngreso
                SET estado = 'en_revision',
                    revisadoPor = ?,
                    motivoRechazo = NULL
                WHERE identificador = ?
                """, employeeId, context.requestId());

        Map<String, String> proposalData = new LinkedHashMap<>();
        proposalData.put("product_id", String.valueOf(context.productId()));
        proposalData.put("product_code", "PROD-%d".formatted(context.productId()));
        proposalData.put("product_name", context.title());
        proposalData.put("reviewed_by", employeeName(employeeId));
        proposalData.put("summary", note);
        proposalData.put("base_price", basePrice.toPlainString());
        proposalData.put("commission_pct", commissionPct.toPlainString());
        proposalData.put("net_amount", basePrice.subtract(basePrice.multiply(commissionPct).divide(new BigDecimal("100"))).toPlainString());
        proposalData.put("flow_kind", "proposal_price");
        proposalData.put("cta_label", "Aceptar propuesta");
        proposalData.put("cta_target", "/notifications");

        Integer messageId = privateMessageService.createPrivateMessage(
                context.ownerId(),
                "aviso_general",
                "Propuesta de valor base para tu artículo",
                "Te enviamos una propuesta económica para continuar con la futura subasta de tu bien.",
                proposalData
        );

        return "La propuesta fue enviada correctamente. Mensaje #" + messageId;
    }

    @Transactional
    public String acceptProduct(Integer productId) {
        Integer employeeId = resolveOperatorId();
        ProductContext context = loadProductContext(productId);

        if (context.requestId() == null) {
            throw new OwnerProductValidationException("No encontramos una solicitud para este producto.");
        }

        jdbcTemplate.update("""
                UPDATE solicitudesIngreso
                SET estado = 'aceptado',
                    revisadoPor = ?,
                    motivoRechazo = NULL
                WHERE identificador = ?
                """, employeeId, context.requestId());

        privateMessageService.createPrivateMessage(
                context.ownerId(),
                "aviso_general",
                "Tu artículo fue aprobado",
                "La inspección resultó favorable. Tu bien quedó listo para ser incluido en una subasta desde el panel de administración.",
                Map.of(
                        "product_id", String.valueOf(context.productId()),
                        "product_code", "PROD-%d".formatted(context.productId()),
                        "product_name", context.title(),
                        "reviewed_by", employeeName(employeeId),
                        "summary", "Tu artículo superó satisfactoriamente la inspección pericial."
                )
        );

        return "El bien fue aprobado correctamente.";
    }

    @Transactional
    public String assignProductInsurance(Integer productId, AssignProductInsuranceRequest request) {
        resolveOperatorId();
        ProductContext context = loadProductContext(productId);
        ensureApprovedProductForInsurance(context);

        String insurancePolicy = request == null || isBlank(request.getInsurancePolicy())
                ? null
                : request.getInsurancePolicy().trim();

        if (insurancePolicy == null) {
            throw new OwnerProductValidationException("Debés indicar una póliza válida.");
        }

        assignInsurancePolicy(context, insurancePolicy);
        return "La póliza fue asignada correctamente al producto.";
    }

    @Transactional
    public String createAndAssignProductInsurance(Integer productId, CreateProductInsuranceRequest request) {
        resolveOperatorId();
        ProductContext context = loadProductContext(productId);
        ensureApprovedProductForInsurance(context);

        if (request == null || isBlank(request.getCompany()) || request.getAmount() == null
                || request.getAmount().compareTo(BigDecimal.ZERO) <= 0 || request.getCombinedPolicy() == null) {
            throw new OwnerProductValidationException("Completá compañía, monto y tipo de póliza para crearla.");
        }

        String insurancePolicy = isBlank(request.getInsurancePolicy())
                ? generateInsurancePolicyNumber()
                : request.getInsurancePolicy().trim();

        Integer existingCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM seguros
                WHERE nroPoliza = ?
                """, Integer.class, insurancePolicy);

        if (existingCount != null && existingCount > 0) {
            throw new OwnerProductValidationException("La póliza indicada ya existe. Elegí otra o reutilizá una válida.");
        }

        jdbcTemplate.update("""
                INSERT INTO seguros (nroPoliza, compania, polizaCombinada, importe)
                VALUES (?, ?, ?, ?)
                """, insurancePolicy, request.getCompany().trim(),
                Boolean.TRUE.equals(request.getCombinedPolicy()) ? "si" : "no",
                request.getAmount());

        insertInsuranceExtension(insurancePolicy, context.ownerId(), nullableTrim(request.getInsurerPhone()));
        assignInsurancePolicy(context, insurancePolicy);

        return "La póliza " + insurancePolicy + " fue creada y asignada correctamente.";
    }

    @Transactional
    public String rejectProduct(Integer productId, RejectProductRequest request) {
        Integer employeeId = resolveOperatorId();
        ProductContext context = loadProductContext(productId);

        if (context.requestId() == null) {
            throw new OwnerProductValidationException("No encontramos una solicitud para este producto.");
        }

        String reasons = joinReasons(request == null ? null : request.getRejectionReasons());
        if (reasons.isBlank()) {
            reasons = "Autenticidad no verificable|Documentación insuficiente|Restauraciones no declaradas|Valor base por debajo del mínimo requerido";
        }

        BigDecimal returnCost = request != null && request.getReturnCost() != null
                ? request.getReturnCost()
                : new BigDecimal("85.00");

        jdbcTemplate.update("""
                UPDATE solicitudesIngreso
                SET estado = 'rechazado',
                    revisadoPor = ?,
                    motivoRechazo = ?,
                    gastosDevolucion = ?
                WHERE identificador = ?
                """, employeeId, reasons, returnCost, context.requestId());

        privateMessageService.createPrivateMessage(
                context.ownerId(),
                "aviso_general",
                "Tu artículo no fue aprobado",
                "La inspección no resultó favorable. Desde este mensaje podés revisar las causas del rechazo.",
                Map.of(
                        "product_id", String.valueOf(context.productId()),
                        "product_code", "PROD-%d".formatted(context.productId()),
                        "product_name", context.title(),
                        "reviewed_by", employeeName(employeeId),
                        "summary", "Lamentamos informarte que el artículo no superó la inspección de nuestros peritos.",
                        "rejection_reasons", reasons,
                        "return_cost", returnCost.toPlainString()
                )
        );

        return "El bien fue rechazado correctamente.";
    }

    @Transactional
    public AdminAuctionCreationResponse createAuction(CreateAuctionRequest request) {
        Integer employeeId = resolveOperatorId();
        List<CreateAuctionLotRequest> lots = resolveLots(request);
        if (lots.isEmpty()) {
            throw new OwnerProductValidationException("Invalid auction request.");
        }

        List<ProductLotContext> selectedLots = new ArrayList<>();
        for (CreateAuctionLotRequest lot : lots) {
            if (lot == null || lot.getProductId() == null || lot.getProductId() <= 0) {
                throw new OwnerProductValidationException("Invalid auction request.");
            }

            ProductContext context = loadProductContext(lot.getProductId());
            if (!"aceptado".equalsIgnoreCase(context.requestStatus())) {
                throw new OwnerProductValidationException("El producto todavía no fue aprobado.");
            }

            if (context.auctionId() != null) {
                throw new OwnerProductValidationException("El producto ya está publicado en una subasta.");
            }

            if (context.photoCount() < 6) {
                throw new OwnerProductValidationException("El producto necesita al menos 6 fotos para publicarse.");
            }

            selectedLots.add(new ProductLotContext(context, lot.getBasePrice()));
        }

        Integer subastadorId = request.getSubastadorId() != null && request.getSubastadorId() > 0
                ? request.getSubastadorId()
                : firstSubastadorId();
        Integer depositId = request.getDepositId() != null && request.getDepositId() > 0 ? request.getDepositId() : null;
        LocalDate auctionDate = request.getAuctionDate() != null ? request.getAuctionDate() : LocalDate.now().plusDays(14);
        LocalTime auctionHour = request.getAuctionHour() != null ? request.getAuctionHour() : LocalTime.of(19, 0);
        ProductLotContext firstLot = selectedLots.get(0);
        ProductContext firstContext = firstLot.context();
        String category = normalizeAuctionCategory(request.getCategory(), firstContext.category());
        String location = isBlank(request.getLocation()) ? "Salón principal" : request.getLocation().trim();
        String description = isBlank(request.getDescription()) ? "Subasta creada desde el panel interno" : request.getDescription().trim();
        String currency = isBlank(request.getCurrency()) ? "ARS" : request.getCurrency().trim().toUpperCase();
        if (request.getAuctionId() == null || request.getAuctionId() <= 0) {
            validateAuctionSchedule(auctionDate, auctionHour);
        }
        Integer auctionId = request.getAuctionId() != null && request.getAuctionId() > 0
                ? request.getAuctionId()
                : insertAuction(subastadorId, auctionDate, auctionHour, location, category, depositId);
        BigDecimal basePrice = request.getBasePrice() != null && request.getBasePrice().compareTo(BigDecimal.ZERO) > 0
                ? request.getBasePrice()
                : firstContext.estimatedValue();
        BigDecimal commission = request.getCommission() != null && request.getCommission().compareTo(BigDecimal.ZERO) > 0
                ? request.getCommission()
                : new BigDecimal("12.50");
        Integer catalogId = insertCatalog(auctionId, employeeId, description);
        List<Integer> itemIds = new ArrayList<>();
        List<Integer> productIds = new ArrayList<>();
        Map<Integer, ProductContext> ownersToNotify = new LinkedHashMap<>();

        for (ProductLotContext lot : selectedLots) {
            ProductContext context = lot.context();
            BigDecimal lotBasePrice = lot.basePrice() != null && lot.basePrice().compareTo(BigDecimal.ZERO) > 0
                    ? lot.basePrice()
                    : context.estimatedValue();

            Integer itemId = insertCatalogItem(catalogId, context.productId(), lotBasePrice, commission);
            itemIds.add(itemId);
            productIds.add(context.productId());
            ownersToNotify.putIfAbsent(context.ownerId(), context);
        }

        for (ProductContext context : ownersToNotify.values()) {
            BigDecimal lotBasePrice = selectedLots.stream()
                    .filter(lot -> lot.context().productId().equals(context.productId()))
                    .findFirst()
                    .map(ProductLotContext::basePrice)
                    .orElse(context.estimatedValue());
            Map<String, String> publicationData = new LinkedHashMap<>();
            publicationData.put("product_id", String.valueOf(context.productId()));
            publicationData.put("auction_id", String.valueOf(auctionId));
            publicationData.put("auction_name", description);
            publicationData.put("base_price", lotBasePrice.toPlainString());
            publicationData.put("currency", currency);
            publicationData.put("flow_kind", "auction_published");
            privateMessageService.createPrivateMessage(
                    context.ownerId(),
                    "aviso_general",
                    "Tu artículo ya forma parte de una subasta",
                    "Tu bien fue incluido en una nueva subasta. Podés seguir su evolución desde tu cuenta.",
                    publicationData
            );
        }

        for (Integer productId : productIds) {
            jdbcTemplate.update("""
                    UPDATE productos
                    SET disponible = 'si'
                    WHERE identificador = ?
                    """, productId);

            if (depositId != null) {
                jdbcTemplate.update("""
                        UPDATE productos_ext
                        SET deposito = ?
                        WHERE identificador = ?
                        """, depositId, productId);
            }
        }

        if (request.getAuctionId() == null || request.getAuctionId() <= 0) {
            jdbcTemplate.update("""
                    INSERT INTO subastas_ext (
                        identificador, moneda, streamingUrl, duenioColeccion, nombreColeccion, observaciones
                    )
                    VALUES (?, ?, ?, NULL, NULL, ?)
                    """, auctionId, currency, "https://streaming.suby.test/subastas/%d".formatted(auctionId), description);
        }

        return AdminAuctionCreationResponse.builder()
                .auctionId(auctionId)
                .catalogId(catalogId)
                .productId(productIds.isEmpty() ? null : productIds.get(0))
                .productIds(productIds)
                .itemId(itemIds.isEmpty() ? null : itemIds.get(0))
                .itemIds(itemIds)
                .lotCount(itemIds.size())
                .message("La subasta fue creada correctamente.")
                .build();
    }

    private List<CreateAuctionLotRequest> resolveLots(CreateAuctionRequest request) {
        if (request == null) {
            return List.of();
        }

        if (request.getLots() != null && !request.getLots().isEmpty()) {
            return request.getLots();
        }

        if (request.getProductIds() != null && !request.getProductIds().isEmpty()) {
            List<CreateAuctionLotRequest> lots = new ArrayList<>();
            for (Integer productId : request.getProductIds()) {
                CreateAuctionLotRequest lot = new CreateAuctionLotRequest();
                lot.setProductId(productId);
                lots.add(lot);
            }
            return lots;
        }

        if (request.getProductId() != null) {
            CreateAuctionLotRequest lot = new CreateAuctionLotRequest();
            lot.setProductId(request.getProductId());
            lot.setBasePrice(request.getBasePrice());
            return List.of(lot);
        }

        return List.of();
    }

    private ProductContext loadProductContext(Integer productId) {
        if (productId == null || productId <= 0) {
            throw new OwnerProductValidationException("Invalid product.");
        }

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        p.identificador AS product_id,
                        p.duenio AS owner_id,
                        owner.nombre AS owner_name,
                        COALESCE(pd.titulo, NULLIF(p.descripcionCatalogo, 'No Posee'), p.descripcionCompleta) AS title,
                        CASE
                            WHEN LOWER(COALESCE(pd.esObraDeArte, 'no')) = 'si' THEN 'arte'
                            WHEN s.identificador IS NOT NULL THEN s.categoria
                            ELSE 'general'
                        END AS category,
                        COALESCE(last_request.identificador, NULL) AS request_id,
                        COALESCE(last_request.estado, 'pendiente') AS request_status,
                        COALESCE(photo_count.total_photos, 0) AS photo_count,
                        COALESCE(ic.precioBase, seg.importe, 0) AS estimated_value,
                        ic.identificador AS auction_item_id,
                        ic.precioBase AS published_base_price,
                        s.identificador AS auction_id,
                        p.seguro AS current_insurance_policy
                    FROM productos p
                    JOIN personas owner ON owner.identificador = p.duenio
                    LEFT JOIN productos_detalle pd ON pd.identificador = p.identificador
                    LEFT JOIN itemsCatalogo ic ON ic.producto = p.identificador
                    LEFT JOIN catalogos c ON c.identificador = ic.catalogo
                    LEFT JOIN subastas s ON s.identificador = c.subasta
                    LEFT JOIN seguros seg ON seg.nroPoliza = p.seguro
                    OUTER APPLY (
                        SELECT COUNT(*) AS total_photos
                        FROM fotos f
                        WHERE f.producto = p.identificador
                    ) photo_count
                    OUTER APPLY (
                        SELECT TOP 1
                            si.identificador,
                            si.estado
                        FROM solicitudesIngreso si
                        WHERE si.duenio = p.duenio
                          AND si.descripcionBien = COALESCE(pd.titulo, NULLIF(p.descripcionCatalogo, 'No Posee'), p.descripcionCompleta)
                        ORDER BY si.fechaSolicitud DESC, si.identificador DESC
                    ) last_request
                    WHERE p.identificador = ?
                    """, (rs, rowNum) -> new ProductContext(
                    rs.getInt("product_id"),
                    rs.getInt("owner_id"),
                    rs.getString("owner_name"),
                    rs.getString("title"),
                    rs.getString("category"),
                    nullableInteger(rs, "request_id"),
                    rs.getString("request_status"),
                    rs.getInt("photo_count"),
                    rs.getBigDecimal("estimated_value"),
                    rs.getBigDecimal("published_base_price"),
                    nullableInteger(rs, "auction_id"),
                    rs.getString("current_insurance_policy")
            ), productId);
        } catch (EmptyResultDataAccessException ex) {
            throw new OwnerProductValidationException("No encontramos el producto indicado.");
        }
    }

    private Integer insertAuction(
            Integer subastadorId,
            LocalDate auctionDate,
            LocalTime auctionHour,
            String location,
            String category,
            Integer depositId
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO subastas (
                        fecha, hora, estado, subastador, ubicacion, capacidadAsistentes,
                        tieneDeposito, seguridadPropia, categoria
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setDate(1, Date.valueOf(auctionDate));
            ps.setObject(2, auctionHour);
            ps.setString(3, "abierta");
            ps.setInt(4, subastadorId);
            ps.setString(5, location);
            ps.setInt(6, 120);
            ps.setString(7, depositId == null ? "no" : "si");
            ps.setString(8, "si");
            ps.setString(9, normalizeAuctionCategory(category, "oro"));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new OwnerProductValidationException("No se pudo crear la subasta.");
        }
        return key.intValue();
    }

    private void validateAuctionSchedule(LocalDate auctionDate, LocalTime auctionHour) {
        if (auctionDate == null || auctionHour == null) {
            throw new OwnerProductValidationException("Debes indicar fecha y hora para la subasta.");
        }

        LocalDateTime scheduledAt = LocalDateTime.of(auctionDate, auctionHour);
        if (!scheduledAt.isAfter(LocalDateTime.now())) {
            throw new OwnerProductValidationException("La fecha y hora de la subasta deben ser posteriores al momento actual.");
        }
    }

    private Integer insertCatalog(Integer auctionId, Integer employeeId, String description) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO catalogos (
                        descripcion, subasta, responsable
                    )
                    VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, description);
            ps.setInt(2, auctionId);
            ps.setInt(3, employeeId);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new OwnerProductValidationException("No se pudo crear el catálogo.");
        }
        return key.intValue();
    }

    private Integer insertCatalogItem(Integer catalogId, Integer productId, BigDecimal basePrice, BigDecimal commission) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO itemsCatalogo (
                        catalogo, producto, precioBase, comision, subastado
                    )
                    VALUES (?, ?, ?, ?, 'no')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, catalogId);
            ps.setInt(2, productId);
            ps.setBigDecimal(3, basePrice);
            ps.setBigDecimal(4, commission);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new OwnerProductValidationException("No se pudo crear el lote.");
        }
        return key.intValue();
    }

    private Integer firstSubastadorId() {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 identificador
                    FROM subastadores
                    ORDER BY identificador ASC
                    """, Integer.class);
        } catch (EmptyResultDataAccessException ex) {
            throw new OwnerProductValidationException("No existe un subastador disponible.");
        }
    }

    private void validateAdminAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("No autorizado.");
        }

        UsuarioApp user = usuarioAppRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("No autorizado."));

        if (ADMIN_EMAIL.equalsIgnoreCase(user.getEmail())) {
            return;
        }

        Integer employeeCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM empleados
                WHERE identificador = ?
                """, Integer.class, user.getIdentificador());

        if (employeeCount == null || employeeCount == 0) {
            throw new UnauthorizedException("No autorizado.");
        }
    }

    private Integer resolveOperatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("No autorizado.");
        }

        UsuarioApp user = usuarioAppRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("No autorizado."));

        if (ADMIN_EMAIL.equalsIgnoreCase(user.getEmail())) {
            return firstEmployeeId();
        }

        Integer employeeCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM empleados
                WHERE identificador = ?
                """, Integer.class, user.getIdentificador());

        if (employeeCount == null || employeeCount == 0) {
            throw new UnauthorizedException("No autorizado.");
        }

        return user.getIdentificador();
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

    private String employeeName(Integer employeeId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 p.nombre
                    FROM empleados e
                    JOIN personas p ON p.identificador = e.identificador
                    WHERE e.identificador = ?
                    """, String.class, employeeId);
        } catch (EmptyResultDataAccessException ex) {
            return "Equipo Suby";
        }
    }

    private String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }

        StringJoiner joiner = new StringJoiner("|");
        for (String reason : reasons) {
            if (reason != null && !reason.isBlank()) {
                joiner.add(reason.trim());
            }
        }
        return joiner.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullableTrim(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void ensureInsuranceExists(String insurancePolicy) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM seguros
                WHERE nroPoliza = ?
                """, Integer.class, insurancePolicy);

        if (count == null || count == 0) {
            throw new OwnerProductValidationException("La póliza indicada no existe.");
        }
    }

    private void ensureInsuranceOwnerCompatibility(String insurancePolicy, Integer ownerId) {
        Integer currentOwner = jdbcTemplate.query("""
                SELECT duenio
                FROM seguros_ext
                WHERE nroPoliza = ?
                """, rs -> rs.next() ? rs.getInt("duenio") : null, insurancePolicy);

        if (currentOwner == null) {
            jdbcTemplate.update("""
                    INSERT INTO seguros_ext (nroPoliza, duenio)
                    VALUES (?, ?)
                    """, insurancePolicy, ownerId);
            return;
        }

        if (!currentOwner.equals(ownerId)) {
            throw new OwnerProductValidationException("La póliza ya está asociada a bienes de otro dueño.");
        }
    }

    private void ensureApprovedProductForInsurance(ProductContext context) {
        if (!"aceptado".equalsIgnoreCase(context.requestStatus())) {
            throw new OwnerProductValidationException("Solo podés asignar una póliza a un producto aprobado.");
        }
    }

    private void ensureInsuranceReusableForProduct(String insurancePolicy, Integer productId) {
        PolicyUsage usage = jdbcTemplate.queryForObject("""
                SELECT
                    SUM(CASE WHEN identificador <> ? THEN 1 ELSE 0 END) AS assigned_elsewhere,
                    SUM(CASE WHEN identificador = ? THEN 1 ELSE 0 END) AS assigned_here
                FROM productos
                WHERE seguro = ?
                """, (rs, rowNum) -> new PolicyUsage(
                rs.getInt("assigned_elsewhere"),
                rs.getInt("assigned_here")
        ), productId, productId, insurancePolicy);

        Integer combinedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM seguros
                WHERE nroPoliza = ?
                  AND polizaCombinada = 'si'
                """, Integer.class, insurancePolicy);

        boolean isCombined = combinedCount != null && combinedCount > 0;
        if (usage == null) {
            return;
        }

        if (!isCombined && usage.assignedElsewhere() > 0) {
            throw new OwnerProductValidationException("La póliza elegida no es combinada y ya está en uso por otro producto.");
        }
    }

    private void assignInsurancePolicy(ProductContext context, String insurancePolicy) {
        ensureInsuranceExists(insurancePolicy);
        ensureInsuranceOwnerCompatibility(insurancePolicy, context.ownerId());
        ensureInsuranceReusableForProduct(insurancePolicy, context.productId());

        jdbcTemplate.update("""
                UPDATE productos
                SET seguro = ?
                WHERE identificador = ?
                """, insurancePolicy, context.productId());

        jdbcTemplate.update("""
                UPDATE productos_ext
                SET nroPoliza = ?
                WHERE identificador = ?
                """, insurancePolicy, context.productId());
    }

    private void insertInsuranceExtension(String insurancePolicy, Integer ownerId, String insurerPhone) {
        if (columnExists("seguros_ext", "nroTelefono")) {
            jdbcTemplate.update("""
                    INSERT INTO seguros_ext (nroPoliza, duenio, nroTelefono)
                    VALUES (?, ?, ?)
                    """, insurancePolicy, ownerId, insurerPhone);
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO seguros_ext (nroPoliza, duenio)
                VALUES (?, ?)
                """, insurancePolicy, ownerId);

        if (tableExists("seguros_contacto_ext")) {
            jdbcTemplate.update("""
                    INSERT INTO seguros_contacto_ext (nroPoliza, nroTelefono)
                    VALUES (?, ?)
                    """, insurancePolicy, insurerPhone);
        }
    }

    private String generateInsurancePolicyNumber() {
        int seed = 1;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM seguros
                """, Integer.class);
        if (count != null && count > 0) {
            seed = count + 1;
        }

        String candidate = "POL-" + LocalDate.now().getYear() + "-" + String.format("%04d", seed);
        Set<String> usedCandidates = new HashSet<>();
        while (usedCandidates.add(candidate) && policyExists(candidate)) {
            seed++;
            candidate = "POL-" + LocalDate.now().getYear() + "-" + String.format("%04d", seed);
        }
        return candidate;
    }

    private boolean policyExists(String insurancePolicy) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM seguros
                WHERE nroPoliza = ?
                """, Integer.class, insurancePolicy);
        return count != null && count > 0;
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

    private String insurerPhoneSelect(String insuranceAlias, String contactAlias) {
        if (columnExists("seguros_ext", "nroTelefono")) {
            return insuranceAlias + ".nroTelefono";
        }

        if (tableExists("seguros_contacto_ext")) {
            return contactAlias + ".nroTelefono";
        }

        return "NULL";
    }

    private String insurerPhoneJoin(String seguroAlias) {
        if (columnExists("seguros_ext", "nroTelefono")) {
            return "";
        }

        if (tableExists("seguros_contacto_ext")) {
            return "LEFT JOIN seguros_contacto_ext sec ON sec.nroPoliza = " + seguroAlias + ".nroPoliza";
        }

        return "";
    }

    private String normalizeAuctionCategory(String rawCategory, String fallbackCategory) {
        String normalized = rawCategory == null ? "" : rawCategory.trim().toLowerCase();
        return switch (normalized) {
            case "comun", "común", "general" -> "comun";
            case "especial", "arte", "artistica", "artística" -> "especial";
            case "plata" -> "plata";
            case "oro" -> "oro";
            case "platino" -> "platino";
            default -> {
                String fallback = fallbackCategory == null ? "" : fallbackCategory.trim().toLowerCase();
                yield switch (fallback) {
                    case "comun", "común", "general" -> "comun";
                    case "especial", "arte", "artistica", "artística" -> "especial";
                    case "plata" -> "plata";
                    case "platino" -> "platino";
                    default -> "oro";
                };
            }
        };
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private Integer nullableInteger(java.sql.ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            if (value == null) {
                return null;
            }
            return rs.getInt(column);
        } catch (Exception ex) {
            return null;
        }
    }

    private String buildItemPhotoUrl(Integer itemId, Integer photoId) {
        if (itemId == null || photoId == null) {
            return null;
        }
        return "/api/v1/auctions/items/%d/photos/%d".formatted(itemId, photoId);
    }

    private record ProductContext(
            Integer productId,
            Integer ownerId,
            String ownerName,
            String title,
            String category,
            Integer requestId,
            String requestStatus,
            Integer photoCount,
            BigDecimal estimatedValue,
            BigDecimal publishedBasePrice,
            Integer auctionId,
            String currentInsurancePolicy
    ) {}

    private record ProductLotContext(ProductContext context, BigDecimal basePrice) {}

    private record PolicyUsage(Integer assignedElsewhere, Integer assignedHere) {}
}
