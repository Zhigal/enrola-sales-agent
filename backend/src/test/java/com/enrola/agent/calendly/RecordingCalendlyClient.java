package com.enrola.agent.calendly;

import java.time.Clock;
import java.time.Instant;

/**
 * The stub with a memory. Booking arguments are otherwise only visible in a log line, and what
 * they contain is a security property worth asserting: the invite must go to the lead on file.
 */
public class RecordingCalendlyClient extends StubCalendlyClient {

    public record Booked(String eventId, String name, String phone, String email,
                         Instant startTime) {}

    private volatile Booked last;

    public RecordingCalendlyClient(Clock clock) {
        super(clock);
    }

    public Booked lastBooking() {
        return last;
    }

    public void reset() {
        last = null;
    }

    @Override
    public String book(String eventId, String name, String phone, String email,
                       Instant startTime) {
        last = new Booked(eventId, name, phone, email, startTime);
        return super.book(eventId, name, phone, email, startTime);
    }
}
