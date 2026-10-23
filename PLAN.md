# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

Turn `CHANGE-REQUEST.md` into three or four agent-sized tasks in the
Supervision Plan format. Every task carries a verification checkpoint, and the
section ends with an out-of-scope list.

**The tasks.** Three or four of them in dependency order. A task is
agent-sized when one agent run can finish it and one gate can tell you whether
it did. For each one, give:

- what behavior it delivers (not "edit module X"), which modules it touches,
  and what it depends on
- its checkpoint, meaning the gate command you run and the observable result
  that would convince you the task is done ("Run the tests" is not a
  checkpoint)
- one specific way the task could go wrong and still leave the gate green

**AGENTS.md rules 3 and 4.** One line: which of your checkpoints catches an
agent that breaks rule 3 (the conflict predicate) or rule 4 (waitlist writes).

**Out of scope, with reasons.** Which modules you are not touching and why. A
reason is a clause in the change request or a rule in AGENTS.md, not "it seemed
unrelated".

### The tasks

Dependency order: T1 → T2 → T3 and T4 (T3 and T4 depend only on T2, not on
each other).

**T1. Modify a booking in the domain: rules, conflict re-check, waitlist, history.**

- *Delivers:* `BookingService.modifyBooking(bookingId, requesterId, newInterval,
  newAttendees)` returns a result that is one of Modified, Unchanged, or Rejected.
  The flow:
  - Owner-only, and only while the booking is upcoming and not cancelled (a new
    `BookingRules.checkModifiable`, next to `checkCancellable`).
  - The new values go through the existing `BookingRules.checkRequest`, so every
    rule in CR clause 3 applies, including room active, not in the past, the
    advance window, and capacity.
  - The room conflict check goes through `ConflictChecker`, run over
    `findActiveByRoom` with the booking itself filtered out by id. The booker's
    own-calendar check does the same over `findActiveByUser`.
  - No cap check (CR 2).
  - On success: save the same booking id with an appended history entry (old
    interval and attendees, new interval and attendees, `at`) on `Booking`.
    *Then*, only if the interval changed, run the existing `promoteWaitlist`
    (CR 4).
  - A modification that changes nothing returns Unchanged and stores nothing
    (CR 5).
  - No event yet. That is T2.
- *Touches:* `core-domain` only (`service/BookingService`, `policy/BookingRules`,
  `domain/Booking`, plus a new result type and history record in `domain/`).
- *Depends on:* nothing.
- *Checkpoint:*
  - `mvn -q -pl core-domain -am test` prints nothing and exits 0. The
    core-domain test count goes up from 167, from a new
    `BookingServiceModifyTest`.
  - That test must cover:
    - a nudge of one 15-minute slot succeeds
    - a move into a taken slot is rejected, and `findBooking` still returns the
      original interval and attendees with empty history
    - a non-owner, a started booking, and a cancelled booking are each refused
    - a booker at `maxActiveBookingsPerUser` can still modify
    - a shorten promotes the waiter for the freed slot
    - an attendee-only change and a lengthen-only change promote nobody
    - a no-op leaves history empty
  - Then the rule 3 and rule 4 check:
    `grep -rnE "\.(save|remove)\(" core-domain/src/main | grep -i waitlist`
    shows only `BookingService.java`.
    `git diff -U0 core-domain/src/main | grep -E '^\+.*(isBefore|isAfter|compareTo)\('`
    shows nothing in conflict code.
- *Could go wrong with the gate green:* `promoteWaitlist` runs *before* the
  modified booking is saved. The store still shows the old interval, so a waiter
  for the time the booking is moving *into* gets promoted, and then the save
  creates a double booking. A test that only shortens never sees it. Guard
  against this with a test that moves a booking onto a waited-for slot and
  asserts `containsMutualConflict` is false.

**T2. Publish `booking-modified`: event log line and owner notification.**

- *Delivers:*
  - A `BookingModifiedEvent` in `core-domain/events`, added to the sealed
    `DomainEvent` permits list. Its core fields carry the *new* interval and
    attendees, plus `previousStart`, `previousEnd`, and `previousAttendees`.
  - `BookingService.modifyBooking` publishes it on Modified only, never on
    Unchanged or Rejected.
  - `EventLogFormat.toLine` writes the three `previous*` fields, and
    `eventTypes()` lists the new type (rule 6).
  - In `notifications`: a `BOOKING_MODIFIED` kind, a template with placeholders
    for both the old and new time and attendees, and the matching arms in the
    two exhaustive switches (`NotificationKind.forEvent`,
    `NotificationService.valuesFor`).
  - Promotions keep using the existing `waitlist-promoted` notice (CR 6).
- *Touches:* `core-domain` (event class and the publish call), `storage`
  (`EventLogFormat`), `notifications` (kind, template, `TemplateRegistry`
  defaults, `Placeholders`).
- *Depends on:* T1.
- *Checkpoint:*
  - `mvn -q -pl storage -am test && mvn -q -pl notifications -am test` both
    print nothing and exit 0. The notifications gate is what proves the sealed
    hierarchy still compiles downstream.
  - The counts rise in all three modules:
    - core-domain: an event is published once on Modified and not on Unchanged
      or Rejected
    - storage: in a new `EventLogTest` case, the line has the 8 core fields in
      order plus the 3 `previous*` fields
    - notifications: in a new `NotificationServiceTest` case, the queued
      message's body contains *both* the old and the new `IntervalText` strings
- *Could go wrong with the gate green:* `valuesFor` computes `WHEN` from
  `event.interval()` for every arm, so the new arm renders only the new time. A
  test that only asserts "one message queued, kind BOOKING_MODIFIED" passes while
  the owner never sees the old details (CR 6). The checkpoint's body assertion on
  *both* strings is what closes this.

**T3. Analytics learns `booking-modified`, and utilization counts current length.**

- *Delivers:*
  - In `analytics/campusreserve_analytics/events.py`: add `BOOKING_MODIFIED` to
    `KNOWN_EVENT_TYPES`, and have `event_from_mapping` accept the `previous*`
    fields.
  - `build_ledger` folds a modified line into the *existing* `BookingRecord`,
    updating `start`, `end`, and `attendees` in place. Because utilization
    already sums `record.duration_minutes`, it counts the current length (CR 8).
  - Revenue reads the same fold and is not touched (CR out of scope).
  - A modified line for a booking never opened is collected the way an orphan
    cancellation is, not dropped.
- *Touches:* `analytics/` only (`events.py`, plus new tests in
  `tests/test_events.py` and `tests/test_utilization.py`).
- *Depends on:* T2's line shape (the contract), but not T2's code.
- *Checkpoint:* from `analytics/`, `python3 -m pytest` passes with more than 137
  tests. The new tests:
  - a 60-minute booking shortened to 30 reports 30 booked minutes
  - a booking moved to another day is attributed to the new day
  - a promoted booking that is later modified still has `from_waitlist == True`
    and its original `created_at`
  - `revenue.py` is unchanged in `git diff --stat`
- *Could go wrong with the gate green:* `booking-modified` is added to
  `HOLDING_EVENT_TYPES`, so the fold rebuilds the record from scratch. That
  resets `from_waitlist` to False and moves `created_at`. The minutes come out
  right, so a utilization-only test passes, but `total_promotions` silently
  drops. The `from_waitlist` assertion above is the guard.

**T4. `PATCH /bookings/{bookingId}`, history on the existing read, and docs.**

- *Delivers:*
  - `BookingsHandler.modify`, registered as
    `router.add("PATCH", "/bookings/{bookingId}", ...)`. It reads optional
    `start`/`end`/`minutes` and `attendees`, and defaults whichever is missing to
    the booking's current value.
  - It passes `account.userId()` to `modifyBooking`, so ownership is decided in
    the domain and the route stays owner-only (CR 7).
  - Responses: 200 with the booking and any promotions. Rejections go through
    `ErrorMapping`: 409 for `ROOM_UNAVAILABLE`/`USER_DOUBLE_BOOKED`, 403 for
    non-owners.
  - `JsonViews.booking` gains a `history` array, so `GET /bookings/{id}` shows
    the past values to the owner or an administrator with no new endpoint
    (CR 5).
  - Docs (CR 9): update the event-type lists in `ARCHITECTURE.md` and `AGENTS.md`
    rule 6, and add `PATCH` to the `ARCHITECTURE.md` HTTP table. The guardrail
    blocks the agent from `AGENTS.md`, so **I make that edit by hand**.
- *Touches:* `http-api` (`BookingsHandler`, `JsonViews`, new `EndpointTest`
  cases), `ARCHITECTURE.md`, `AGENTS.md` (by hand).
- *Depends on:* T2.
- *Checkpoint:*
  - `mvn -q -pl http-api -am test` prints nothing and exits 0, with the http-api
    count above 78. The new tests:
    - owner PATCH returns 200 and the new times
    - PATCH into a taken slot returns 409, and a follow-up GET shows the
      original
    - **administrator** PATCH on someone else's booking returns 403
    - GET after a modify shows one `history` entry
  - Then `mvn -B clean test` is green cold.
  - `grep -n "booking-modified" AGENTS.md ARCHITECTURE.md` and
    `grep -n PATCH ARCHITECTURE.md` each hit.
- *Could go wrong with the gate green:* the handler guards with
  `auth.requireActingFor(request, booking.userId())`, copied from `show`. That
  lets an administrator through, then passes the *booking owner's* id to the
  service, so admins can modify anyone's booking. Every owner-path test still
  passes. The admin-gets-403 test is the guard.

**AGENTS.md rules 3 and 4.** T1's checkpoint catches both. Its `grep` shows
waitlist `save`/`remove` only in `BookingService` (rule 4). Its diff grep finds
no new start/end comparisons, and the self-exclusion is a list filter fed to
`ConflictChecker` (rule 3). T4 re-runs the same two greps over `http-api/src/main`.

### Out of scope, with reasons

- **`billing`**: CR "The price does not change … Do not re-price, re-issue, or
  void and re-invoice." The invoice snapshot already freezes an issued price.
- **`cli`**: CR "Nothing in the CLI." It renders existing fields and needs no
  new command. A cold `mvn -B clean test` still compiles it against the new
  `Booking`.
- **`accounts`**: CR "Nothing in account management. Modification uses the
  token and ownership checks that already exist."
- **`analytics/campusreserve_analytics/revenue.py`** and its tests: CR "The
  revenue report is not part of this change … do not filter modified bookings
  out … do not fork the reading." It changes only through the shared ledger fold
  in T3.
- **Changing rooms**: CR "The room does not change." `modifyBooking` takes no
  room id.
- **Storage stores and the `core/ports` interfaces**: `BookingStore.save`
  already upserts by id (it is how `cancelBooking` stores a cancellation), so no
  port changes. Rule 1: nothing gains a dependency edge.
- **Any JSON library on the Java side**: rule 6. History and the new log fields
  are written by hand through `JsonObject`/`JsonLine`.
- **Shipped tests**: rule 7. Every new assertion goes in a new test method. No
  existing test is edited to go green.

---

## Milestone 2: The guardrail

Pick one recipe from `guardrails/README.md`. Configure it, then prove it binds.

**The recipe.** Which one you picked, the exact paths or trees it covers, and
one line on what it cannot catch.

**The captured block.** Paste the excerpt of the agent's own turn showing what
you asked it to do, its tool call, and the block the guardrail returned. A bare
terminal paste with no agent turn around it does not show the boundary binding.

See revenue-edit-denied-by-hook.png. 
This hook setup won't catch Bash commands, sed -i, cp, or Python one-liner writes.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**The takeover point.** Write this before you start the run. A concrete,
conditional statement of when you would stop the agent and write the code
yourself. "If something looks wrong" does not count. Name the trigger and name
what you would do.

1. While editing. 
- Trigger: Agent edits a file outside core-domain/, edits a shipped test, writes files with Bash. 
- Action: stop the agent, run git status, revert the file. 

2. When diff exists
- Trigger: A second overlap check exists, promoteWaitlist runs before modified booking is saved, waitlist writes outside BookingService
- Action: stop the agent, modify the diff manually. 

3. At the gate (running the tests)
- Trigger: tests are stil red after 2 agent fix attempts; test count doesn't rise from 167. 
- Action: stop the agent, read the failure & finish the code by hand; write the missing test myself without asking the agent.

**The evidence.** Name the task from Milestone 1. Paste the result line of its
gate command (run it without `-q` so the line is in the output) and the result
line of one `mvn -B clean test` run after the task was finished. The module
gate cannot see a cross-module compile break on a warm tree. A cold build can,
and so can CI.

### Task
T1, the domain logic edits. 

### Gate's result line run
mvn -pl core-domain -am test 

Tests run: 186, Failures: 0, Errors: 0, Skipped: 0

### Clean build run
mvn -B clean test

[INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0

**The diff.** The files the agent changed, and how each one maps to that task's
declared scope (plain `git diff` leaves out new files, but
`git add -N . && git diff --stat` includes them). Note anything it touched that
you did not expect, including any shipped test. Then quote the one line you
read hardest and say what you were checking for.

No unexpected files were touched. A line I read the most: modifyBooking in BookingService, since core logic is centered there. I was also checking for where promoteWaitlist ran, as per the takeover point plan.

**The refuter.** What you gave it and what you asked it to do (paste the
brief). Then the one finding you settled, and how (the new failing test with
its red run, or the evidence that dismissed it). If nothing it reported held
up, say how you checked its strongest claim.

### Brief (Prompt)
you are a refuter. read CHANGE-REQUEST.md, the current diff (git add -N . && git diff). The task: T1. Modify a booking in the domain: rules, conflict re-check, waitlist, history. Do not read anything else in the codebase. find inputs or call sequences where the task does the wrong thing.

### Finding
A modification with zero or negative attendees throws an exception instead of being rejected.
  - Call: modifyBooking(id, "u-1", SLOT, 0) or modifyBooking(id, "u-1", SLOT, -5) on an upcoming booking the caller owns.
  - What happened: IllegalArgumentException: Attendees must be positive: 0.
  - Why: the interval is the same but the attendee count differs, so the call gets past the Unchanged check. BookingRequest.of(...) then throws before checkRequest can return a reason.
  - Every other bad input comes back as Rejected (wrong owner, started, cancelled, capacity, past, alignment, opening hours, too long, too far ahead), so this one breaks the method's own contract. Nothing gets saved, so the booking itself survives. But T4's PATCH will pass the body's attendees straight in and probably return a 500, not a 400/422.
  - Suggested test: modifyBooking(id, owner, SLOT, 0) should be isRejected(), with the stored booking unchanged and its history empty.

### Finding test
aNonPositiveAttendeeCountIsRejectedNotThrown in BookingServiceModifyTest.

### Kept or dismissed
Dismissed. Error traces back to BookingRequest creation, which throws. The refuter's arguments, dismissed with evidence:
1. Throwing "breaks contract": constructor throws instead of rejects. A reject would require modifyBooking() to add a newAttendees > 0 check. This wasn't required by CHANGE-REQUEST.md, and was a part of the original implementation.
2. T4 PATCH will allow non-positive attendee counts: T4 is not implemented yet. Do not add a redundant test for an unimplemented feature.