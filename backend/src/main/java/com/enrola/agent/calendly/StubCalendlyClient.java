package com.enrola.agent.calendly;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in for Calendly. Deterministic given a fixed Clock, which is what makes the agent's
 * time reasoning testable at all. Single concrete class, no interface - there is one
 * implementation, and swapping in a real client when a key arrives is a small change.
 */
@Component
public class StubCalendlyClient {

    private static final Logger log = LoggerFactory.getLogger(StubCalendlyClient.class);

    private static final LocalTime FIRST = LocalTime.of(9, 0);
    private static final LocalTime LAST = LocalTime.of(16, 30);
    private static final Duration STEP = Duration.ofMinutes(30);
    private static final Duration LEAD_TIME = Duration.ofHours(1);
    private static final int MAX_SLOTS = 20;

    // ponytail: one hardcoded busy block rather than a fake calendar. It reproduces the
    // "no mid-morning spot, here's the nearest" case on demand. Replace wholesale when a
    // real Calendly key arrives.
    private static final Set<LocalTime> BUSY =
            Set.of(LocalTime.of(10, 0), LocalTime.of(10, 30));

    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong(1000);

    public StubCalendlyClient(Clock clock) {
        this.clock = clock;
    }

    public List<Instant> availableTimes(ZoneId timezone, Instant startTime, Instant endTime) {
        var earliest = clock.instant().plus(LEAD_TIME);
        var from = startTime.isBefore(earliest) ? earliest : startTime;
        var slots = new ArrayList<Instant>();

        var day = from.atZone(timezone).toLocalDate();
        var lastDay = endTime.atZone(timezone).toLocalDate();

        while (!day.isAfter(lastDay) && slots.size() < MAX_SLOTS) {
            if (isBusinessDay(day)) {
                for (var time = FIRST; !time.isAfter(LAST); time = time.plus(STEP)) {
                    if (BUSY.contains(time)) {
                        continue;
                    }
                    var slot = day.atTime(time).atZone(timezone).toInstant();
                    if (!slot.isBefore(from) && !slot.isAfter(endTime) && slots.size() < MAX_SLOTS) {
                        slots.add(slot);
                    }
                }
            }
            day = day.plusDays(1);
        }
        return slots;
    }

    public String book(String eventId, String name, String phone, String email, Instant startTime) {
        var id = "cal_" + sequence.incrementAndGet();
        log.info("Stub booking {} on event {} for {} at {}", id, eventId, phone, startTime);
        return id;
    }

    private static boolean isBusinessDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
