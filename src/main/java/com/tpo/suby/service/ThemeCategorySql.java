package com.tpo.suby.service;

final class ThemeCategorySql {

    private ThemeCategorySql() {
    }

    static String themeCategoryCase(String detailAlias, String productAlias) {
        return """
                CASE
                    WHEN LOWER(LTRIM(RTRIM(COALESCE(%1$s.categoriaTematica, '')))) IN ('arte', 'joyeria', 'vehiculos', 'mobiliario', 'otros')
                        THEN LOWER(LTRIM(RTRIM(%1$s.categoriaTematica)))
                    WHEN LOWER(COALESCE(%1$s.esObraDeArte, 'no')) = 'si'
                        THEN 'arte'
                    WHEN LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%joy%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%reloj%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%diamante%%'
                        THEN 'joyeria'
                    WHEN LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%veh%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%auto%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%moto%%'
                        THEN 'vehiculos'
                    WHEN LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%mobili%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%antig%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%mesa%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%sillon%%'
                         OR LOWER(COALESCE(%2$s.descripcionCatalogo, '') + ' ' + COALESCE(%2$s.descripcionCompleta, '')) LIKE '%%decor%%'
                        THEN 'mobiliario'
                    ELSE 'otros'
                END
                """.formatted(detailAlias, productAlias);
    }
}
