package com.tpo.suby.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionScheduleServiceTest {

    private final AuctionScheduleService service = new AuctionScheduleService();

    @Test
    void keepsAuctionUpcomingBeforeScheduledTime() {
        LocalDate today = LocalDate.of(2026, 6, 29);
        LocalTime scheduledTime = LocalTime.of(20, 0);
        LocalDateTime now = LocalDateTime.of(2026, 6, 29, 18, 30);

        assertEquals("proxima", service.calculatedStatus("abierta", today, scheduledTime, now));
        assertFalse(service.hasStarted(today, scheduledTime, "abierta", now));
    }

    @Test
    void startsAuctionExactlyAtScheduledTime() {
        LocalDate today = LocalDate.of(2026, 6, 29);
        LocalTime scheduledTime = LocalTime.of(20, 0);
        LocalDateTime now = LocalDateTime.of(2026, 6, 29, 20, 0);

        assertEquals("en_vivo", service.calculatedStatus("abierta", today, scheduledTime, now));
        assertTrue(service.hasStarted(today, scheduledTime, "abierta", now));
    }

    @Test
    void keepsClosedAuctionAsFinished() {
        LocalDate today = LocalDate.of(2026, 6, 29);
        LocalTime scheduledTime = LocalTime.of(20, 0);
        LocalDateTime now = LocalDateTime.of(2026, 6, 29, 20, 1);

        assertEquals("finalizada", service.calculatedStatus("cerrada", today, scheduledTime, now));
    }
}
