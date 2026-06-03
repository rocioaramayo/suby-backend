USE suby;
GO

/*
    Seed demo para crear una subasta en vivo/proxima con catalogo y lotes visibles.
    El script es idempotente: si ya existe la subasta demo, reutiliza esa misma.
*/

DECLARE @ConstraintName NVARCHAR(200);
DECLARE @Sql NVARCHAR(MAX);

SELECT @ConstraintName = cc.name
FROM sys.check_constraints cc
JOIN sys.tables t ON cc.parent_object_id = t.object_id
JOIN sys.columns c ON c.object_id = t.object_id
WHERE t.name = 'subastas'
  AND c.name = 'fecha'
  AND cc.definition LIKE '%DATEADD%';

IF @ConstraintName IS NOT NULL
BEGIN
    SET @Sql = 'ALTER TABLE subastas DROP CONSTRAINT ' + QUOTENAME(@ConstraintName);
    EXEC sp_executesql @Sql;
    PRINT 'Constraint eliminado temporalmente: ' + @ConstraintName;
END;
GO

BEGIN TRANSACTION;

DECLARE @NuevaSubastaId INT;
DECLARE @CatalogoId INT;
DECLARE @Producto1 INT;
DECLARE @Producto2 INT;
DECLARE @Producto3 INT;
DECLARE @SubastadorId INT;
DECLARE @RevisorId INT;
DECLARE @DuenioId INT;
DECLARE @Sql NVARCHAR(MAX);
DECLARE @SeedObservation NVARCHAR(200) = 'Subasta demo seeded desde scripts/demo-live-auction.sql';

SELECT TOP 1 @SubastadorId = identificador
FROM subastadores
ORDER BY identificador ASC;

SELECT TOP 1 @RevisorId = identificador
FROM empleados
ORDER BY identificador ASC;

SELECT TOP 1 @DuenioId = identificador
FROM duenios
ORDER BY identificador ASC;

IF @SubastadorId IS NULL OR @RevisorId IS NULL OR @DuenioId IS NULL
BEGIN
    RAISERROR('Faltan datos base: se necesita al menos un subastador, un empleado y un duenio.', 16, 1);
    ROLLBACK TRANSACTION;
    RETURN;
END;

SELECT @NuevaSubastaId = se.identificador
FROM subastas_ext se
WHERE se.observaciones = @SeedObservation;

IF @NuevaSubastaId IS NULL
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
        CAST(GETDATE() AS DATE),
        CAST(DATEADD(MINUTE, 10, GETDATE()) AS TIME),
        'abierta',
        @SubastadorId,
        'Av. Corrientes 1234',
        120,
        'si',
        'si',
        'comun'
    );

    SET @NuevaSubastaId = SCOPE_IDENTITY();

    DECLARE @SubastasExtColumns NVARCHAR(MAX) = N'identificador, moneda, streamingUrl, observaciones';
    DECLARE @SubastasExtValues NVARCHAR(MAX) = N'@NuevaSubastaId, ''ARS'', NULL, @SeedObservation';

    IF COL_LENGTH('subastas_ext', 'duenioColeccion') IS NOT NULL
    BEGIN
        SET @SubastasExtColumns += N', duenioColeccion';
        SET @SubastasExtValues += N', NULL';
    END;

    IF COL_LENGTH('subastas_ext', 'nombreColeccion') IS NOT NULL
    BEGIN
        SET @SubastasExtColumns += N', nombreColeccion';
        SET @SubastasExtValues += N', NULL';
    END;

    SET @Sql = N'INSERT INTO subastas_ext (' + @SubastasExtColumns + N') VALUES (' + @SubastasExtValues + N');';
    EXEC sp_executesql
        @Sql,
        N'@NuevaSubastaId INT, @SeedObservation NVARCHAR(200)',
        @NuevaSubastaId = @NuevaSubastaId,
        @SeedObservation = @SeedObservation;
END;

SELECT @CatalogoId = identificador
FROM catalogos
WHERE subasta = @NuevaSubastaId;

IF @CatalogoId IS NULL
BEGIN
    INSERT INTO catalogos (
        descripcion,
        subasta,
        responsable
    )
    VALUES (
        'Catalogo demo para home y busqueda',
        @NuevaSubastaId,
        @RevisorId
    );

    SET @CatalogoId = SCOPE_IDENTITY();
END;

IF NOT EXISTS (
    SELECT 1
    FROM itemsCatalogo
    WHERE catalogo = @CatalogoId
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
        'Reloj antiguo de coleccion',
        'Reloj antiguo de coleccion con detalles metalicos, ideal para una subasta premium.',
        @RevisorId,
        @DuenioId,
        NULL
    );
    SET @Producto1 = SCOPE_IDENTITY();

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
        'Pintura decorativa firmada',
        'Pintura decorativa con marco incluido, pieza unica para coleccion privada.',
        @RevisorId,
        @DuenioId,
        NULL
    );
    SET @Producto2 = SCOPE_IDENTITY();

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
        'Joya antigua dorada',
        'Joya antigua con terminaciones doradas, revisada y aprobada para catalogo.',
        @RevisorId,
        @DuenioId,
        NULL
    );
    SET @Producto3 = SCOPE_IDENTITY();

    INSERT INTO itemsCatalogo (
        catalogo,
        producto,
        precioBase,
        comision,
        subastado
    )
    VALUES
    (
        @CatalogoId,
        @Producto1,
        150000.00,
        15000.00,
        'no'
    ),
    (
        @CatalogoId,
        @Producto2,
        220000.00,
        22000.00,
        'no'
    ),
    (
        @CatalogoId,
        @Producto3,
        95000.00,
        9500.00,
        'no'
    );
END;

COMMIT TRANSACTION;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.check_constraints
    WHERE parent_object_id = OBJECT_ID('subastas')
      AND name = 'chk_subastas_fecha_minima'
)
BEGIN
    ALTER TABLE subastas WITH NOCHECK
    ADD CONSTRAINT chk_subastas_fecha_minima
    CHECK (fecha > DATEADD(DAY, 10, GETDATE()));
END;
GO

SELECT s.*
FROM subastas s
JOIN subastas_ext se ON se.identificador = s.identificador
WHERE se.observaciones = 'Subasta demo seeded desde scripts/demo-live-auction.sql';

SELECT c.*
FROM catalogos c
JOIN subastas_ext se ON se.identificador = c.subasta
WHERE se.observaciones = 'Subasta demo seeded desde scripts/demo-live-auction.sql';

SELECT
    ic.identificador AS itemCatalogoId,
    ic.catalogo,
    ic.producto,
    p.descripcionCatalogo,
    p.descripcionCompleta,
    ic.precioBase,
    ic.comision,
    ic.subastado
FROM itemsCatalogo ic
JOIN productos p ON p.identificador = ic.producto
JOIN catalogos c ON c.identificador = ic.catalogo
JOIN subastas_ext se ON se.identificador = c.subasta
WHERE se.observaciones = 'Subasta demo seeded desde scripts/demo-live-auction.sql'
ORDER BY ic.identificador ASC;
GO
