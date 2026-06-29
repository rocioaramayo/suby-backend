package com.tpo.suby.service;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

@Service
public class AuctionScheduleService {

    static final ZoneId AUCTION_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private static final Set<String> CLOSED_STATES = Set.of(
            "cerrada", "terminada", "closed", "ended", "completed", "finalizada"
    );

    public ZoneId zone() {
        return AUCTION_ZONE;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(AUCTION_ZONE);
    }

    public Timestamp nowTimestamp() {
        return Timestamp.valueOf(now());
    }

    public LocalDateTime scheduledAt(LocalDate auctionDate, LocalTime auctionTime) {
        return LocalDateTime.of(
                auctionDate == null ? now().toLocalDate() : auctionDate,
                auctionTime == null ? LocalTime.MIDNIGHT : auctionTime
        );
    }

    public boolean hasStarted(LocalDate auctionDate, LocalTime auctionTime, String persistedState) {
        return hasStarted(auctionDate, auctionTime, persistedState, now());
    }

    boolean hasStarted(LocalDate auctionDate, LocalTime auctionTime, String persistedState, LocalDateTime now) {
        return "en_vivo".equals(calculatedStatus(persistedState, auctionDate, auctionTime, now));
    }

    public String calculatedStatus(String persistedState, LocalDate auctionDate, LocalTime auctionTime) {
        return calculatedStatus(persistedState, auctionDate, auctionTime, now());
    }

    String calculatedStatus(String persistedState, LocalDate auctionDate, LocalTime auctionTime, LocalDateTime now) {
        if (isClosedState(persistedState)) {
            return "finalizada";
        }

        return now.isBefore(scheduledAt(auctionDate, auctionTime))
                ? "proxima"
                : "en_vivo";
    }

    public boolean isClosedState(String persistedState) {
        String normalized = normalize(persistedState);
        return CLOSED_STATES.contains(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
