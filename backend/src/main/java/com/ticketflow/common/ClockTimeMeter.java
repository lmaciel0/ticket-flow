package com.ticketflow.common;

import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Instant;

/** Bucket4j's notion of "now", read from the application Clock so tests can move time instead of sleeping. */
class ClockTimeMeter implements TimeMeter {

    private final Clock clock;

    ClockTimeMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long currentTimeNanos() {
        Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
