package com.ticketflow.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** A clock the tests control: it stands still until a test moves it forward. */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock() {
        reset();
    }

    /** Back to the real "now", truncated to microseconds (PostgreSQL's precision). */
    public void reset() {
        instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** A snapshot of the current instant in the requested zone. */
    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }
}
