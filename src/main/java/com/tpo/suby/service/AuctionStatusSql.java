package com.tpo.suby.service;

final class AuctionStatusSql {

    private static final String ACTIVE_STATE_SET = "('abierta', 'activa', 'en_vivo', 'live', 'open')";

    private AuctionStatusSql() {
    }

    static String activeStateFilter(String stateColumn) {
        return "LOWER(LTRIM(RTRIM(COALESCE(%s, '')))) IN %s".formatted(stateColumn, ACTIVE_STATE_SET);
    }

    static String normalizedStatusCase(String stateColumn, String dateColumn) {
        String activeStateFilter = activeStateFilter(stateColumn);
        return """
                CASE
                    WHEN %s AND CAST(%s AS DATE) = CAST(GETDATE() AS DATE) THEN 'en_vivo'
                    WHEN %s AND CAST(%s AS DATE) > CAST(GETDATE() AS DATE) THEN 'proxima'
                    ELSE 'finalizada'
                END
                """.formatted(activeStateFilter, dateColumn, activeStateFilter, dateColumn);
    }

    static String principalFlowFilter(String stateColumn, String dateColumn) {
        return "(%s IN ('en_vivo', 'proxima'))".formatted(normalizedStatusCase(stateColumn, dateColumn));
    }
}
