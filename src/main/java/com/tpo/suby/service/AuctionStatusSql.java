package com.tpo.suby.service;

final class AuctionStatusSql {

    private static final String ACTIVE_STATE_SET = "('abierta', 'activa', 'en_vivo', 'live', 'open')";
    private static final String ARGENTINA_NOW =
            "CAST(SYSUTCDATETIME() AT TIME ZONE 'UTC' AT TIME ZONE 'Argentina Standard Time' AS DATETIME2)";

    private AuctionStatusSql() {
    }

    static String activeStateFilter(String stateColumn) {
        return "LOWER(LTRIM(RTRIM(COALESCE(%s, '')))) IN %s".formatted(stateColumn, ACTIVE_STATE_SET);
    }

    static String normalizedStatusCase(String stateColumn, String dateColumn) {
        String activeStateFilter = activeStateFilter(stateColumn);
        return """
                CASE
                    WHEN %s
                         AND CAST(CONCAT(CONVERT(varchar(10), %s, 120), ' ', CONVERT(varchar(8), COALESCE(s.hora, '00:00:00'), 108)) AS DATETIME2) <= %s
                    THEN 'en_vivo'
                    WHEN %s
                         AND CAST(CONCAT(CONVERT(varchar(10), %s, 120), ' ', CONVERT(varchar(8), COALESCE(s.hora, '00:00:00'), 108)) AS DATETIME2) > %s
                    THEN 'proxima'
                    ELSE 'finalizada'
                END
                """.formatted(activeStateFilter, dateColumn, ARGENTINA_NOW, activeStateFilter, dateColumn, ARGENTINA_NOW);
    }

    static String principalFlowFilter(String stateColumn, String dateColumn) {
        return "(%s IN ('en_vivo', 'proxima'))".formatted(normalizedStatusCase(stateColumn, dateColumn));
    }
}
