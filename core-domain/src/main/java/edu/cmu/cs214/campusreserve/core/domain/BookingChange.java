package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry in a booking's modification history: what the booking held before,
 * what it holds after, and when the change was made.
 */
public record BookingChange(
    TimeInterval previousInterval,
    int previousAttendees,
    TimeInterval newInterval,
    int newAttendees,
    Instant at) {

    public BookingChange {
        Objects.requireNonNull(previousInterval, "previousInterval");
        Objects.requireNonNull(newInterval, "newInterval");
        Objects.requireNonNull(at, "at");
        if (previousAttendees <= 0 || newAttendees <= 0) {
            throw new IllegalArgumentException(
                "Attendees must be positive: " + previousAttendees + ", " + newAttendees);
        }
    }

    /** Did the change move or resize the interval? */
    public boolean intervalChanged() {
        return !previousInterval.equals(newInterval);
    }
}
