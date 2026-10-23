package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What came of a modification. A successful modification reports the bookings
 * that were promoted off the waitlist into the time it freed. A modification
 * that asked for what the booking already holds changes nothing and records
 * nothing.
 */
public sealed interface ModificationResult {

    /** The booking now holds the new values. {@code promotions} may be empty. */
    record Modified(Booking booking, List<CancellationResult.Promotion> promotions)
        implements ModificationResult {
        public Modified {
            Objects.requireNonNull(booking, "booking");
            promotions = List.copyOf(promotions);
        }
    }

    /** The booking already held the requested values. Nothing was stored. */
    record Unchanged(Booking booking) implements ModificationResult {
        public Unchanged {
            Objects.requireNonNull(booking, "booking");
        }
    }

    /** The modification was refused. Nothing changed. */
    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? reason.message() : detail;
        }
    }

    /** Builds a rejection carrying the reason's own message. */
    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason, reason.message());
    }

    /** Did the modification change the booking? */
    default boolean isModified() {
        return this instanceof Modified;
    }

    /** Did the modification ask for what the booking already held? */
    default boolean isUnchanged() {
        return this instanceof Unchanged;
    }

    /** Was the modification refused? */
    default boolean isRejected() {
        return this instanceof Rejected;
    }

    /** The booking as it now stands, unless the modification was refused. */
    default Optional<Booking> currentBooking() {
        if (this instanceof Modified modified) {
            return Optional.of(modified.booking());
        }
        if (this instanceof Unchanged unchanged) {
            return Optional.of(unchanged.booking());
        }
        return Optional.empty();
    }

    /** Promotions triggered by the modification, empty unless it went through. */
    default List<CancellationResult.Promotion> promotions() {
        return this instanceof Modified modified ? modified.promotions() : List.of();
    }

    /** The reason, when the modification was refused. */
    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
