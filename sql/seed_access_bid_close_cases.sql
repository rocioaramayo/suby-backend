USE suby;
GO

SET NOCOUNT ON;
GO

/*
Seed de pruebas para:
1. acceso a sala por categoria
2. acceso a sala por medio verificado
3. sesion unica por usuario
4. moneda ARS/USD
5. cierre automatico

Credenciales para los 3 clientes:
- cliente.a.flow@test.com / Suby1234!
- cliente.b.flow@test.com / Suby1234!
- cliente.c.flow@test.com / Suby1234!

Notas:
- Crea una subasta USD y una ARS, ambas categoria oro.
- Usuario A queda debajo de categoria.
- Usuario B tiene categoria suficiente pero sin medio verificado.
- Usuario C tiene categoria suficiente y medios verificados compatibles.
- El bloque final para cierre automatico es opcional porque la constraint chkFecha
  impide crear subastas "ya vencidas" en un esquema limpio.
*/

DECLARE @PasswordHash VARCHAR(256) = '$2a$10$E8wurRIM.E4WxgYT.low2.zZvpexkfyr6mLIaz3O05dufHA0wPBpa';

DECLARE @PaisAR INT = 54;
DECLARE @PaisUS INT = 840;

IF NOT EXISTS (SELECT 1 FROM paises WHERE numero = @PaisAR)
BEGIN
    INSERT INTO paises (numero, nombre, nombreCorto, capital, nacionalidad, idiomas)
    VALUES (@PaisAR, 'Argentina', 'AR', 'Buenos Aires', 'argentina', 'espanol');
END;

IF NOT EXISTS (SELECT 1 FROM paises WHERE numero = @PaisUS)
BEGIN
    INSERT INTO paises (numero, nombre, nombreCorto, capital, nacionalidad, idiomas)
    VALUES (@PaisUS, 'Estados Unidos', 'US', 'Washington', 'estadounidense', 'ingles');
END;

DECLARE @EmpleadoId INT;
DECLARE @SubastadorId INT;
DECLARE @DuenioId INT;
DECLARE @ClienteAId INT;
DECLARE @ClienteBId INT;
DECLARE @ClienteCId INT;
DECLARE @SubastaUsdId INT;
DECLARE @SubastaArsId INT;
DECLARE @CatalogoUsdId INT;
DECLARE @CatalogoArsId INT;
DECLARE @ProductoUsdId INT;
DECLARE @ProductoArsId INT;
DECLARE @ItemUsdId INT;
DECLARE @ItemArsId INT;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'EMP-FLOW-001')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('EMP-FLOW-001', 'Empleado Flow QA', 'Oficina Central', 'activo', NULL);
END;

SELECT @EmpleadoId = identificador
FROM personas
WHERE documento = 'EMP-FLOW-001';

IF NOT EXISTS (SELECT 1 FROM empleados WHERE identificador = @EmpleadoId)
BEGIN
    INSERT INTO empleados (identificador, cargo, sector)
    VALUES (@EmpleadoId, 'QA Tester', NULL);
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'SUB-FLOW-001')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('SUB-FLOW-001', 'Subastador Flow QA', 'Sala Principal', 'activo', NULL);
END;

SELECT @SubastadorId = identificador
FROM personas
WHERE documento = 'SUB-FLOW-001';

IF NOT EXISTS (SELECT 1 FROM subastadores WHERE identificador = @SubastadorId)
BEGIN
    INSERT INTO subastadores (identificador, matricula, region)
    VALUES (@SubastadorId, 'MAT-FLOW-001', 'CABA');
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'DUE-FLOW-001')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('DUE-FLOW-001', 'Duenio Flow QA', 'Deposito Norte', 'activo', NULL);
END;

SELECT @DuenioId = identificador
FROM personas
WHERE documento = 'DUE-FLOW-001';

IF NOT EXISTS (SELECT 1 FROM duenios WHERE identificador = @DuenioId)
BEGIN
    INSERT INTO duenios (
        identificador, numeroPais, verificacionFinanciera, verificacionJudicial,
        calificacionRiesgo, verificador
    )
    VALUES (
        @DuenioId, @PaisAR, 'si', 'si',
        1, @EmpleadoId
    );
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'CLI-FLOW-A')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('CLI-FLOW-A', 'Cliente Flow A', 'Direccion A', 'activo', NULL);
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'CLI-FLOW-B')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('CLI-FLOW-B', 'Cliente Flow B', 'Direccion B', 'activo', NULL);
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'CLI-FLOW-C')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('CLI-FLOW-C', 'Cliente Flow C', 'Direccion C', 'activo', NULL);
END;

SELECT @ClienteAId = identificador FROM personas WHERE documento = 'CLI-FLOW-A';
SELECT @ClienteBId = identificador FROM personas WHERE documento = 'CLI-FLOW-B';
SELECT @ClienteCId = identificador FROM personas WHERE documento = 'CLI-FLOW-C';

IF NOT EXISTS (SELECT 1 FROM clientes WHERE identificador = @ClienteAId)
BEGIN
    INSERT INTO clientes (identificador, numeroPais, admitido, categoria, verificador)
    VALUES (@ClienteAId, @PaisAR, 'si', 'comun', @EmpleadoId);
END;

IF NOT EXISTS (SELECT 1 FROM clientes WHERE identificador = @ClienteBId)
BEGIN
    INSERT INTO clientes (identificador, numeroPais, admitido, categoria, verificador)
    VALUES (@ClienteBId, @PaisAR, 'si', 'oro', @EmpleadoId);
END;

IF NOT EXISTS (SELECT 1 FROM clientes WHERE identificador = @ClienteCId)
BEGIN
    INSERT INTO clientes (identificador, numeroPais, admitido, categoria, verificador)
    VALUES (@ClienteCId, @PaisAR, 'si', 'platino', @EmpleadoId);
END;

IF NOT EXISTS (SELECT 1 FROM usuarios_app WHERE identificador = @ClienteAId)
BEGIN
    INSERT INTO usuarios_app (
        identificador, email, passwordHash, tokenRecuperacion, tokenExpira,
        estadoApp, ultimoLogin, intentosFallidos, bloqueadoHasta
    )
    VALUES (
        @ClienteAId, 'cliente.a.flow@test.com', @PasswordHash, NULL, NULL,
        'activo', NULL, 0, NULL
    );
END;

IF NOT EXISTS (SELECT 1 FROM usuarios_app WHERE identificador = @ClienteBId)
BEGIN
    INSERT INTO usuarios_app (
        identificador, email, passwordHash, tokenRecuperacion, tokenExpira,
        estadoApp, ultimoLogin, intentosFallidos, bloqueadoHasta
    )
    VALUES (
        @ClienteBId, 'cliente.b.flow@test.com', @PasswordHash, NULL, NULL,
        'activo', NULL, 0, NULL
    );
END;

IF NOT EXISTS (SELECT 1 FROM usuarios_app WHERE identificador = @ClienteCId)
BEGIN
    INSERT INTO usuarios_app (
        identificador, email, passwordHash, tokenRecuperacion, tokenExpira,
        estadoApp, ultimoLogin, intentosFallidos, bloqueadoHasta
    )
    VALUES (
        @ClienteCId, 'cliente.c.flow@test.com', @PasswordHash, NULL, NULL,
        'activo', NULL, 0, NULL
    );
END;

IF NOT EXISTS (
    SELECT 1
    FROM subastas s
    WHERE s.ubicacion = 'FLOW-QA-USD'
)
BEGIN
    INSERT INTO subastas (
        fecha, hora, estado, subastador, ubicacion,
        capacidadAsistentes, tieneDeposito, seguridadPropia, categoria
    )
    VALUES (
        CAST(DATEADD(DAY, 15, GETDATE()) AS DATE),
        CAST('15:00:00' AS TIME),
        'abierta',
        @SubastadorId,
        'FLOW-QA-USD',
        50,
        'si',
        'si',
        'oro'
    );
END;

SELECT @SubastaUsdId = identificador
FROM subastas
WHERE ubicacion = 'FLOW-QA-USD';

IF NOT EXISTS (SELECT 1 FROM subastas_ext WHERE identificador = @SubastaUsdId)
BEGIN
    INSERT INTO subastas_ext (identificador, moneda, streamingUrl, duenioColeccion, nombreColeccion, observaciones)
    VALUES (@SubastaUsdId, 'USD', 'https://stream.flow/usd', NULL, NULL, 'Seed QA flujo acceso/puja/cierre');
END;

IF NOT EXISTS (
    SELECT 1
    FROM subastas s
    WHERE s.ubicacion = 'FLOW-QA-ARS'
)
BEGIN
    INSERT INTO subastas (
        fecha, hora, estado, subastador, ubicacion,
        capacidadAsistentes, tieneDeposito, seguridadPropia, categoria
    )
    VALUES (
        CAST(DATEADD(DAY, 16, GETDATE()) AS DATE),
        CAST('18:00:00' AS TIME),
        'abierta',
        @SubastadorId,
        'FLOW-QA-ARS',
        50,
        'si',
        'si',
        'oro'
    );
END;

SELECT @SubastaArsId = identificador
FROM subastas
WHERE ubicacion = 'FLOW-QA-ARS';

IF NOT EXISTS (SELECT 1 FROM subastas_ext WHERE identificador = @SubastaArsId)
BEGIN
    INSERT INTO subastas_ext (identificador, moneda, streamingUrl, duenioColeccion, nombreColeccion, observaciones)
    VALUES (@SubastaArsId, 'ARS', 'https://stream.flow/ars', NULL, NULL, 'Seed QA flujo acceso/puja/cierre');
END;

IF NOT EXISTS (
    SELECT 1
    FROM productos
    WHERE descripcionCompleta = 'Producto QA USD - Flujo Acceso/Puja/Cierre'
)
BEGIN
    INSERT INTO productos (
        fecha, disponible, descripcionCatalogo, descripcionCompleta, revisor, duenio, seguro
    )
    VALUES (
        CAST(GETDATE() AS DATE),
        'si',
        'Pintura QA USD',
        'Producto QA USD - Flujo Acceso/Puja/Cierre',
        @EmpleadoId,
        @DuenioId,
        NULL
    );
END;

SELECT @ProductoUsdId = identificador
FROM productos
WHERE descripcionCompleta = 'Producto QA USD - Flujo Acceso/Puja/Cierre';

IF NOT EXISTS (
    SELECT 1
    FROM productos
    WHERE descripcionCompleta = 'Producto QA ARS - Flujo Acceso/Puja/Cierre'
)
BEGIN
    INSERT INTO productos (
        fecha, disponible, descripcionCatalogo, descripcionCompleta, revisor, duenio, seguro
    )
    VALUES (
        CAST(GETDATE() AS DATE),
        'si',
        'Escultura QA ARS',
        'Producto QA ARS - Flujo Acceso/Puja/Cierre',
        @EmpleadoId,
        @DuenioId,
        NULL
    );
END;

SELECT @ProductoArsId = identificador
FROM productos
WHERE descripcionCompleta = 'Producto QA ARS - Flujo Acceso/Puja/Cierre';

IF NOT EXISTS (
    SELECT 1
    FROM catalogos
    WHERE subasta = @SubastaUsdId
      AND descripcion = 'Catalogo QA USD'
)
BEGIN
    INSERT INTO catalogos (descripcion, subasta, responsable)
    VALUES ('Catalogo QA USD', @SubastaUsdId, @EmpleadoId);
END;

SELECT @CatalogoUsdId = identificador
FROM catalogos
WHERE subasta = @SubastaUsdId
  AND descripcion = 'Catalogo QA USD';

IF NOT EXISTS (
    SELECT 1
    FROM catalogos
    WHERE subasta = @SubastaArsId
      AND descripcion = 'Catalogo QA ARS'
)
BEGIN
    INSERT INTO catalogos (descripcion, subasta, responsable)
    VALUES ('Catalogo QA ARS', @SubastaArsId, @EmpleadoId);
END;

SELECT @CatalogoArsId = identificador
FROM catalogos
WHERE subasta = @SubastaArsId
  AND descripcion = 'Catalogo QA ARS';

IF NOT EXISTS (
    SELECT 1
    FROM itemsCatalogo
    WHERE catalogo = @CatalogoUsdId
      AND producto = @ProductoUsdId
)
BEGIN
    INSERT INTO itemsCatalogo (catalogo, producto, precioBase, comision, subastado)
    VALUES (@CatalogoUsdId, @ProductoUsdId, 52000.00, 15.00, 'no');
END;

SELECT @ItemUsdId = identificador
FROM itemsCatalogo
WHERE catalogo = @CatalogoUsdId
  AND producto = @ProductoUsdId;

IF NOT EXISTS (
    SELECT 1
    FROM itemsCatalogo
    WHERE catalogo = @CatalogoArsId
      AND producto = @ProductoArsId
)
BEGIN
    INSERT INTO itemsCatalogo (catalogo, producto, precioBase, comision, subastado)
    VALUES (@CatalogoArsId, @ProductoArsId, 3500000.00, 12.00, 'no');
END;

SELECT @ItemArsId = identificador
FROM itemsCatalogo
WHERE catalogo = @CatalogoArsId
  AND producto = @ProductoArsId;

IF NOT EXISTS (
    SELECT 1
    FROM mediosDePago mdp
    WHERE mdp.cliente = @ClienteAId
      AND mdp.tipo = 'cuenta_bancaria'
      AND mdp.moneda = 'ARS'
)
BEGIN
    INSERT INTO mediosDePago (cliente, tipo, estado, moneda, montoDisponible, montoUsado, fechaVencimiento)
    VALUES (@ClienteAId, 'cuenta_bancaria', 'verificado', 'ARS', 9000000.00, 0, NULL);

    INSERT INTO cuentasBancarias (identificador, banco, numeroCuenta, tipoCuenta, pais, cbu, swift, iban)
    VALUES (SCOPE_IDENTITY(), 'Banco QA A', 'A-ARS-0001', 'corriente', @PaisAR, '0000003100000000000001', NULL, NULL);
END;

IF NOT EXISTS (
    SELECT 1
    FROM mediosDePago mdp
    WHERE mdp.cliente = @ClienteBId
      AND mdp.tipo = 'cuenta_bancaria'
)
BEGIN
    INSERT INTO mediosDePago (cliente, tipo, estado, moneda, montoDisponible, montoUsado, fechaVencimiento)
    VALUES (@ClienteBId, 'cuenta_bancaria', 'pendiente', 'ARS', 9000000.00, 0, NULL);

    INSERT INTO cuentasBancarias (identificador, banco, numeroCuenta, tipoCuenta, pais, cbu, swift, iban)
    VALUES (SCOPE_IDENTITY(), 'Banco QA B', 'B-ARS-0001', 'corriente', @PaisAR, '0000003100000000000002', NULL, NULL);
END;

IF NOT EXISTS (
    SELECT 1
    FROM mediosDePago mdp
    WHERE mdp.cliente = @ClienteCId
      AND mdp.tipo = 'cuenta_bancaria'
      AND mdp.moneda = 'USD'
)
BEGIN
    INSERT INTO mediosDePago (cliente, tipo, estado, moneda, montoDisponible, montoUsado, fechaVencimiento)
    VALUES (@ClienteCId, 'cuenta_bancaria', 'verificado', 'USD', 120000.00, 0, NULL);

    INSERT INTO cuentasBancarias (identificador, banco, numeroCuenta, tipoCuenta, pais, cbu, swift, iban)
    VALUES (SCOPE_IDENTITY(), 'Bank QA USD', 'C-USD-0001', 'extranjera', @PaisUS, NULL, 'BOFAUS3N', 'US00FLOW0000000000001');
END;

IF NOT EXISTS (
    SELECT 1
    FROM mediosDePago mdp
    WHERE mdp.cliente = @ClienteCId
      AND mdp.tipo = 'cuenta_bancaria'
      AND mdp.moneda = 'ARS'
)
BEGIN
    INSERT INTO mediosDePago (cliente, tipo, estado, moneda, montoDisponible, montoUsado, fechaVencimiento)
    VALUES (@ClienteCId, 'cuenta_bancaria', 'verificado', 'ARS', 99000000.00, 0, NULL);

    INSERT INTO cuentasBancarias (identificador, banco, numeroCuenta, tipoCuenta, pais, cbu, swift, iban)
    VALUES (SCOPE_IDENTITY(), 'Banco QA C', 'C-ARS-0001', 'corriente', @PaisAR, '0000003100000000000003', NULL, NULL);
END;

IF NOT EXISTS (
    SELECT 1
    FROM mediosDePago mdp
    WHERE mdp.cliente = @ClienteCId
      AND mdp.tipo = 'tarjeta_credito'
)
BEGIN
    INSERT INTO mediosDePago (cliente, tipo, estado, moneda, montoDisponible, montoUsado, fechaVencimiento)
    VALUES (@ClienteCId, 'tarjeta_credito', 'verificado', 'USD', 0, 0, DATEADD(YEAR, 2, CAST(GETDATE() AS DATE)));

    INSERT INTO tarjetasCredito (identificador, nombreTitular, numeroEnmascarado, redTarjeta, esInternacional, pais)
    VALUES (SCOPE_IDENTITY(), 'Cliente Flow C', '**** 4242', 'visa', 'si', @PaisUS);
END;

IF NOT EXISTS (
    SELECT 1
    FROM mediosDePago mdp
    WHERE mdp.cliente = @ClienteCId
      AND mdp.tipo = 'cheque_certificado'
      AND mdp.moneda = 'USD'
)
BEGIN
    INSERT INTO mediosDePago (cliente, tipo, estado, moneda, montoDisponible, montoUsado, fechaVencimiento)
    VALUES (@ClienteCId, 'cheque_certificado', 'verificado', 'USD', 80000.00, 0, NULL);

    INSERT INTO chequesCertificados (
        identificador, banco, numeroCheque, montoGarantia, fechaEntrega, verificadoPor, subasta
    )
    VALUES (
        SCOPE_IDENTITY(), 'Bank QA USD', 'CHK-FLOW-USD-01', 80000.00, CAST(GETDATE() AS DATE), @EmpleadoId, @SubastaUsdId
    );
END;

SELECT
    @ClienteAId AS usuario_a_id,
    @ClienteBId AS usuario_b_id,
    @ClienteCId AS usuario_c_id,
    @SubastaUsdId AS subasta_usd_id,
    @SubastaArsId AS subasta_ars_id,
    @ItemUsdId AS item_usd_id,
    @ItemArsId AS item_ars_id;
GO

/*
Bloque opcional para forzar cierre automatico en ambiente de prueba.
Usalo solo despues de haber cargado un asistente y al menos una puja.

1. Deja una subasta abierta pero con fecha/hora ya vencida.
2. Espera hasta 60 segundos para que corra AuctionLifecycleService.
3. Verifica registro, notificacion, mensaje y liberacion de sesion.
*/

/*
ALTER TABLE subastas NOCHECK CONSTRAINT chkFecha;

UPDATE s
SET
    s.fecha = CAST(DATEADD(DAY, -1, GETDATE()) AS DATE),
    s.hora = CAST(DATEADD(MINUTE, -240, GETDATE()) AS TIME),
    s.estado = 'abierta'
FROM subastas s
WHERE s.ubicacion = 'FLOW-QA-USD';

-- Espera 60 segundos y luego valida:
SELECT s.identificador, s.estado, s.fecha, s.hora
FROM subastas s
WHERE s.ubicacion = 'FLOW-QA-USD';

SELECT ic.identificador, ic.subastado
FROM itemsCatalogo ic
JOIN catalogos c ON c.identificador = ic.catalogo
JOIN subastas s ON s.identificador = c.subasta
WHERE s.ubicacion = 'FLOW-QA-USD';

SELECT pu.identificador, pu.item, pu.importe, pu.ganador
FROM pujos pu
WHERE pu.item IN (
    SELECT ic.identificador
    FROM itemsCatalogo ic
    JOIN catalogos c ON c.identificador = ic.catalogo
    JOIN subastas s ON s.identificador = c.subasta
    WHERE s.ubicacion = 'FLOW-QA-USD'
);

SELECT *
FROM registroDeSubasta
WHERE subasta = (
    SELECT identificador FROM subastas WHERE ubicacion = 'FLOW-QA-USD'
);

SELECT *
FROM notificacionesPago
WHERE registro IN (
    SELECT identificador
    FROM registroDeSubasta
    WHERE subasta = (
        SELECT identificador FROM subastas WHERE ubicacion = 'FLOW-QA-USD'
    )
);

SELECT *
FROM mensajes_privados
WHERE tipo = 'ganador_subasta'
  AND destinatario = (
      SELECT identificador FROM personas WHERE documento = 'CLI-FLOW-C'
  );

SELECT *
FROM sesiones_usuario
WHERE persona = (
    SELECT identificador FROM personas WHERE documento = 'CLI-FLOW-C'
);
*/
