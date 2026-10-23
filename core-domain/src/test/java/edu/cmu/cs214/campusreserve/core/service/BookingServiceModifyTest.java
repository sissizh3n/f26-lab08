package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingChange;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.policy.ConflictChecker;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookingServiceModifyTest extends BookingServiceTestBase {

    private static final TimeInterval SLOT = Fixtures.interval("10:00", "11:00");

    private Booking stored(String bookingId) {
        return service.findBooking(bookingId).orElseThrow();
    }

    private boolean roomHasMutualConflict(String roomId) {
        return new ConflictChecker().containsMutualConflict(bookings.findActiveByRoom(roomId));
    }

    @Test
    void nudgingByOneSlotSucceeds() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        TimeInterval nudged = Fixtures.interval("10:15", "11:15");

        ModificationResult result = service.modifyBooking(id, "u-1", nudged, 4);

        assertTrue(result.isModified());
        Booking booking = stored(id);
        assertEquals(nudged, booking.interval());
        assertEquals(Optional.of(booking), result.currentBooking());
    }

    @Test
    void aSuccessfulModificationAppendsOneHistoryEntry() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        TimeInterval moved = Fixtures.interval("13:00", "14:00");

        service.modifyBooking(id, "u-1", moved, 6);

        Booking booking = stored(id);
        assertEquals(6, booking.attendees());
        assertEquals(List.of(new BookingChange(SLOT, 4, moved, 6, Instant.parse(Fixtures.NOW))),
            booking.history());
    }

    @Test
    void historyAccumulatesInOrder() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        TimeInterval first = Fixtures.interval("10:15", "11:15");
        TimeInterval second = Fixtures.interval("10:30", "11:30");

        service.modifyBooking(id, "u-1", first, 4);
        service.modifyBooking(id, "u-1", second, 4);

        List<BookingChange> history = stored(id).history();
        assertEquals(2, history.size());
        assertEquals(SLOT, history.get(0).previousInterval());
        assertEquals(first, history.get(1).previousInterval());
        assertEquals(second, history.get(1).newInterval());
    }

    @Test
    void theModifiedBookingKeepsItsIdOwnerRoomAndCreationTime() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        Booking before = stored(id);

        service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        Booking after = stored(id);
        assertEquals(before.id(), after.id());
        assertEquals(before.roomId(), after.roomId());
        assertEquals(before.userId(), after.userId());
        assertEquals(before.createdAt(), after.createdAt());
        assertEquals(before.status(), after.status());
        assertEquals(1, bookings.count());
    }

    @Test
    void aMoveIntoATakenSlotIsRejectedAndTheOriginalStands() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-1", "u-2", Fixtures.interval("11:00", "12:00"));

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("10:30", "11:30"), 6);

        assertEquals(Optional.of(RejectionReason.ROOM_UNAVAILABLE), result.rejectionReason());
        Booking booking = stored(id);
        assertEquals(SLOT, booking.interval());
        assertEquals(4, booking.attendees());
        assertTrue(booking.history().isEmpty());
        assertEquals(0, waitlist.count());
    }

    @Test
    void aMoveOntoTheBookersOtherBookingIsRejected() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        bookAndGetId("r-2", "u-1", Fixtures.interval("12:00", "13:00"));

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        assertEquals(Optional.of(RejectionReason.USER_DOUBLE_BOOKED), result.rejectionReason());
        assertEquals(SLOT, stored(id).interval());
    }

    @Test
    void aNonOwnerIsRefused() {
        String id = bookAndGetId("r-1", "u-1", SLOT);

        ModificationResult result =
            service.modifyBooking(id, "u-2", Fixtures.interval("12:00", "13:00"), 4);

        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), result.rejectionReason());
        assertEquals(SLOT, stored(id).interval());
    }

    @Test
    void aStartedBookingIsRefused() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        clock.setTo(Fixtures.DAY + "T10:00:00Z");

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_STARTED), result.rejectionReason());
        assertEquals(SLOT, stored(id).interval());
    }

    @Test
    void aCancelledBookingIsRefused() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        service.cancelBooking(id, "u-1");

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED), result.rejectionReason());
        assertFalse(stored(id).holdsRoom());
    }

    @Test
    void anUnknownBookingIsRefused() {
        ModificationResult result = service.modifyBooking("bk-missing", "u-1", SLOT, 4);
        assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), result.rejectionReason());
    }

    @Test
    void theRequestRulesStillApply() {
        String id = bookAndGetId("r-1", "u-1", SLOT);

        assertEquals(Optional.of(RejectionReason.ATTENDEES_EXCEED_CAPACITY),
            service.modifyBooking(id, "u-1", SLOT, 11).rejectionReason());
        assertEquals(Optional.of(RejectionReason.INTERVAL_IN_PAST),
            service.modifyBooking(id, "u-1",
                Fixtures.intervalOn("2026-10-19", "08:00", "09:00"), 4).rejectionReason());
        assertEquals(Optional.of(RejectionReason.INTERVAL_NOT_ALIGNED),
            service.modifyBooking(id, "u-1", Fixtures.interval("10:05", "11:05"), 4).rejectionReason());
        assertEquals(Optional.of(RejectionReason.OUTSIDE_OPENING_HOURS),
            service.modifyBooking(id, "u-1", Fixtures.interval("21:30", "22:30"), 4).rejectionReason());

        Booking booking = stored(id);
        assertEquals(SLOT, booking.interval());
        assertEquals(4, booking.attendees());
        assertTrue(booking.history().isEmpty());
    }

    @Test
    void aNonPositiveAttendeeCountIsRejectedNotThrown() {
        String id = bookAndGetId("r-1", "u-1", SLOT);

        assertTrue(service.modifyBooking(id, "u-1", SLOT, 0).isRejected());
        assertTrue(service.modifyBooking(id, "u-1", SLOT, -5).isRejected());

        Booking booking = stored(id);
        assertEquals(SLOT, booking.interval());
        assertEquals(4, booking.attendees());
        assertTrue(booking.history().isEmpty());
    }

    @Test
    void aBookerAtTheCapCanStillModify() {
        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
        String id = bookAndGetId("r-1", "u-1", SLOT);

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

        assertTrue(result.isModified());
    }

    @Test
    void shorteningPromotesTheWaiterForTheFreedSlot() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertTrue(book("r-1", "u-2", Fixtures.interval("10:30", "11:00")).isWaitlisted());

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "10:30"), 4);

        assertEquals(1, result.promotions().size());
        assertEquals("u-2", result.promotions().get(0).booking().userId());
        assertEquals(0, waitlist.count());
        assertFalse(roomHasMutualConflict("r-1"));
    }

    @Test
    void anAttendeeOnlyChangeStartsNoPromotionPass() {
        // u-2 waits for 10:00 behind u-3. Once that slot has passed, a promotion
        // pass would drop the stale entry; an attendee-only change must not run one.
        bookAndGetId("r-1", "u-3", SLOT);
        assertTrue(book("r-1", "u-2", SLOT).isWaitlisted());
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("14:00", "15:00"));
        clock.setTo(Fixtures.DAY + "T10:30:00Z");

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 8);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, waitlist.count());
    }

    @Test
    void lengtheningOnlyPromotesNobody() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertTrue(book("r-1", "u-2", SLOT).isWaitlisted());

        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:30"), 4);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, waitlist.count());
        assertFalse(roomHasMutualConflict("r-1"));
    }

    @Test
    void movingOntoAWaitedForSlotNeverDoubleBooks() {
        // u-2 waits for r-1 12:00 behind u-3, then takes r-2 at 12:00 too, so when
        // u-3 cancels the promotion pass skips u-2. Once u-2 lets r-2 go, the r-1
        // slot is free but u-2 is still waiting for it.
        String blocker = bookAndGetId("r-1", "u-3", Fixtures.interval("12:00", "13:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("12:00", "13:00")).isWaitlisted());
        String elsewhere = bookAndGetId("r-2", "u-2", Fixtures.interval("12:00", "13:00"));
        assertTrue(service.cancelBooking(blocker, "u-3").promotions().isEmpty());
        service.cancelBooking(elsewhere, "u-2");
        assertEquals(1, waitlist.count());

        String id = bookAndGetId("r-1", "u-1", SLOT);
        ModificationResult result =
            service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, waitlist.count());
        assertFalse(roomHasMutualConflict("r-1"));
    }

    @Test
    void aNoOpIsUnchangedAndRecordsNothing() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        Booking before = stored(id);

        ModificationResult result = service.modifyBooking(id, "u-1", SLOT, 4);

        assertTrue(result.isUnchanged());
        assertEquals(before, stored(id));
        assertTrue(stored(id).history().isEmpty());
    }

    @Test
    void aNoOpBySomeoneElseIsStillRefused() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER),
            service.modifyBooking(id, "u-2", SLOT, 4).rejectionReason());
    }

    @Test
    void cancellingAModifiedBookingKeepsItsHistory() {
        String id = bookAndGetId("r-1", "u-1", SLOT);
        service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);

        service.cancelBooking(id, "u-1");

        assertEquals(1, stored(id).history().size());
    }
}
