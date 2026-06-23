USE suby;
GO

SET NOCOUNT ON;
GO

/*
Seed rapido:
- Crea una subasta categoria oro
- Moneda ARS
- Comienza dentro de 2 minutos
- Deja 1 lote listo para entrar a sala

Nota:
- Si tu base mantiene activa la constraint chkFecha original, este script la desactiva
  porque esa regla exige subastas con mas de 10 dias de anticipacion.
*/

ALTER TABLE subastas NOCHECK CONSTRAINT chkFecha;
GO

DECLARE @PaisAR INT = 54;
DECLARE @EmpleadoId INT;
DECLARE @SubastadorId INT;
DECLARE @DuenioId INT;
DECLARE @SubastaId INT;
DECLARE @CatalogoId INT;
DECLARE @ProductoId INT;

DECLARE @FechaInicio DATE = CAST(DATEADD(MINUTE, 2, GETDATE()) AS DATE);
DECLARE @HoraInicio TIME = CAST(DATEADD(MINUTE, 2, GETDATE()) AS TIME);

IF NOT EXISTS (SELECT 1 FROM paises WHERE numero = @PaisAR)
BEGIN
    INSERT INTO paises (numero, nombre, nombreCorto, capital, nacionalidad, idiomas)
    VALUES (@PaisAR, 'Argentina', 'AR', 'Buenos Aires', 'argentina', 'espanol');
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'EMP-ORO-ARS-2MIN')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('EMP-ORO-ARS-2MIN', 'Empleado Seed Oro ARS', 'Oficina Central', 'activo', NULL);
END;

SELECT @EmpleadoId = identificador
FROM personas
WHERE documento = 'EMP-ORO-ARS-2MIN';

IF NOT EXISTS (SELECT 1 FROM empleados WHERE identificador = @EmpleadoId)
BEGIN
    INSERT INTO empleados (identificador, cargo, sector)
    VALUES (@EmpleadoId, 'QA Seed', NULL);
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'SUB-ORO-ARS-2MIN')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('SUB-ORO-ARS-2MIN', 'Subastador Oro ARS', 'Sala Oro', 'activo', NULL);
END;

SELECT @SubastadorId = identificador
FROM personas
WHERE documento = 'SUB-ORO-ARS-2MIN';

IF NOT EXISTS (SELECT 1 FROM subastadores WHERE identificador = @SubastadorId)
BEGIN
    INSERT INTO subastadores (identificador, matricula, region)
    VALUES (@SubastadorId, 'MAT-ORO-ARS', 'CABA');
END;

IF NOT EXISTS (SELECT 1 FROM personas WHERE documento = 'DUE-ORO-ARS-2MIN')
BEGIN
    INSERT INTO personas (documento, nombre, direccion, estado, foto)
    VALUES ('DUE-ORO-ARS-2MIN', 'Duenio Oro ARS', 'Deposito Centro', 'activo', NULL);
END;

SELECT @DuenioId = identificador
FROM personas
WHERE documento = 'DUE-ORO-ARS-2MIN';

IF NOT EXISTS (SELECT 1 FROM duenios WHERE identificador = @DuenioId)
BEGIN
    INSERT INTO duenios (
        identificador,
        numeroPais,
        verificacionFinanciera,
        verificacionJudicial,
        calificacionRiesgo,
        verificador
    )
    VALUES (
        @DuenioId,
        @PaisAR,
        'si',
        'si',
        1,
        @EmpleadoId
    );
END;

IF NOT EXISTS (
    SELECT 1
    FROM subastas
    WHERE ubicacion = 'SEED-ORO-ARS-2MIN'
)
BEGIN
    INSERT INTO subastas (
        fecha,
        hora,
        estado,
        subastador,
        ubicacion,
        capacidadAsistentes,
        tieneDeposito,
        seguridadPropia,
        categoria
    )
    VALUES (
        @FechaInicio,
        @HoraInicio,
        'abierta',
        @SubastadorId,
        'SEED-ORO-ARS-2MIN',
        50,
        'si',
        'si',
        'oro'
    );
END;

SELECT @SubastaId = identificador
FROM subastas
WHERE ubicacion = 'SEED-ORO-ARS-2MIN';

IF NOT EXISTS (SELECT 1 FROM subastas_ext WHERE identificador = @SubastaId)
BEGIN
    INSERT INTO subastas_ext (
        identificador,
        moneda,
        streamingUrl,
        duenioColeccion,
        nombreColeccion,
        observaciones
    )
    VALUES (
        @SubastaId,
        'ARS',
        'https://streaming.suby.test/subastas/oro-ars-2min',
        NULL,
        NULL,
        'Seed oro ARS que inicia dentro de 2 minutos'
    );
END;

IF NOT EXISTS (
    SELECT 1
    FROM productos
    WHERE descripcionCompleta = 'Producto seed oro ARS 2min'
)
BEGIN
    INSERT INTO productos (
        fecha,
        disponible,
        descripcionCatalogo,
        descripcionCompleta,
        revisor,
        duenio,
        seguro
    )
    VALUES (
        CAST(GETDATE() AS DATE),
        'si',
        'Reloj seed oro ARS',
        'Producto seed oro ARS 2min',
        @EmpleadoId,
        @DuenioId,
        NULL
    );
END;

SELECT @ProductoId = identificador
FROM productos
WHERE descripcionCompleta = 'Producto seed oro ARS 2min';

IF NOT EXISTS (
    SELECT 1
    FROM catalogos
    WHERE subasta = @SubastaId
      AND descripcion = 'Catalogo seed oro ARS 2min'
)
BEGIN
    INSERT INTO catalogos (descripcion, subasta, responsable)
    VALUES ('Catalogo seed oro ARS 2min', @SubastaId, @EmpleadoId);
END;

SELECT @CatalogoId = identificador
FROM catalogos
WHERE subasta = @SubastaId
  AND descripcion = 'Catalogo seed oro ARS 2min';

IF NOT EXISTS (
    SELECT 1
    FROM itemsCatalogo
    WHERE catalogo = @CatalogoId
      AND producto = @ProductoId
)
BEGIN
    INSERT INTO itemsCatalogo (
        catalogo,
        producto,
        precioBase,
        comision,
        subastado
    )
    VALUES (
        @CatalogoId,
        @ProductoId,
        7500.00,
        15.00,
        'no'
    );
END;

SELECT
    s.identificador AS subastaId,
    s.fecha,
    s.hora,
    s.estado,
    s.categoria,
    se.moneda,
    c.identificador AS catalogoId,
    ic.identificador AS itemId,
    ic.precioBase,
    p.identificador AS productoId,
    p.descripcionCompleta
FROM subastas s
JOIN subastas_ext se ON se.identificador = s.identificador
JOIN catalogos c ON c.subasta = s.identificador
JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
JOIN productos p ON p.identificador = ic.producto
WHERE s.identificador = @SubastaId;
GO
