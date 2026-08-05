package com.enrola.agent.calendly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StubCalendlyClientTest {

    // The stub takes a zone as an input parameter and knows nothing about customers - a plain
    // literal here is correct, not a shortcut. Reaching into a customer registry from this test
    // would couple a zone-agnostic stub to config it never consults in production.
    private static final ZoneId PERTH = ZoneId.of("Australia/Perth");

    // Wednesday 2026-08-05, 08:00 Perth time. Business hours start at 09:00, so the fixed
    // clock sits before the first bookable slot of the day.
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    private final StubCalendlyClient client =
            new StubCalendlyClient(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void offersOnlyBusinessHourSlotsInTheCustomerTimezone() {
        var slots = client.availableTimes(PERTH, NOW, NOW.plus(Duration.ofDays(3)));

        assertThat(slots).isNotEmpty();
        assertThat(slots).allSatisfy(slot -> {
            var local = slot.atZone(PERTH).toLocalTime();
            assertThat(local).isBetween(LocalTime.of(9, 0), LocalTime.of(16, 30));
            assertThat(local.getMinute() % 30).isZero();
        });
    }

    @Test
    void neverOffersAWeekend() {
        // The window must START near the weekend. NOW is a Wednesday, and 14 bookable slots a
        // day means MAX_SLOTS (20) is reached partway through Thursday - so a Wednesday-anchored
        // 14-day query never reaches Saturday, and this assertion would hold even with the
        // weekend check deleted. Anchoring on Friday makes it a real test: Fri fills, Sat and
        // Sun must contribute nothing, Mon takes the remainder.
        // 4 days, not 3: friday+3d lands at Monday 08:00 Perth, before the 09:00 first slot,
        // so a 3-day window returns Friday only and never reaches Monday at all.
        var friday = NOW.plus(Duration.ofDays(2));
        var slots = client.availableTimes(PERTH, friday, friday.plus(Duration.ofDays(4)));

        assertThat(slots).isNotEmpty();
        assertThat(slots).allSatisfy(slot -> {
            var day = slot.atZone(PERTH).getDayOfWeek().getValue();
            assertThat(day).isLessThanOrEqualTo(5);
        });
        // Proves the window genuinely spanned the weekend rather than stopping short of it.
        assertThat(slots).anySatisfy(slot ->
                assertThat(slot.atZone(PERTH).getDayOfWeek())
                        .isEqualTo(java.time.DayOfWeek.MONDAY));
    }

    @Test
    void neverOffersAPastSlotOrOneLessThanAnHourAway() {
        var slots = client.availableTimes(PERTH, NOW.minus(Duration.ofDays(2)),
                NOW.plus(Duration.ofDays(2)));

        assertThat(slots).allSatisfy(slot ->
                assertThat(slot).isAfterOrEqualTo(NOW.plus(Duration.ofHours(1))));
    }

    @Test
    void midMorningIsAlwaysBusy() {
        var slots = client.availableTimes(PERTH, NOW, NOW.plus(Duration.ofDays(7)));

        assertThat(slots).noneSatisfy(slot ->
                assertThat(slot.atZone(PERTH).getHour()).isEqualTo(10));
    }

    @Test
    void isDeterministic() {
        assertThat(client.availableTimes(PERTH, NOW, NOW.plus(Duration.ofDays(5))))
                .isEqualTo(client.availableTimes(PERTH, NOW, NOW.plus(Duration.ofDays(5))));
    }

    @Test
    void bookingReturnsAStableIdShape() {
        var id = client.book("evt_stub_comparato", "John", "+61457099876",
                "john@example.com", NOW.plus(Duration.ofDays(1)));

        assertThat(id).startsWith("cal_");
    }
}
