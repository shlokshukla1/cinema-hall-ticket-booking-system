package com.cinemahall;

import com.cinemahall.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Business rules and negative cases around holds, confirmation and cancellation. */
class BookingRulesTest extends ApiTestBase {

    @Test
    void repeatingAnIdempotencyKeyReturnsTheOriginalBooking() throws Exception {
        ShowFixture show = createShow();
        String customer = customer();
        String hold = holdReference(customer, show.showId(), show.seatIds().get(0));

        Response first = expect(201, confirm(customer, hold, "double-click"));
        Response replay = expect(200, confirm(customer, hold, "double-click"));

        assertThat(replay.body().get("reference")).isEqualTo(first.body().get("reference"));
        assertThat(replay.raw().getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(get(customer, "/api/bookings").body().get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void anIdempotencyKeyCannotBeReusedForADifferentHold() throws Exception {
        ShowFixture show = createShow();
        String customer = customer();
        expect(201, confirm(customer, holdReference(customer, show.showId(), show.seatIds().get(0)), "same-key"));
        String other = holdReference(customer, show.showId(), show.seatIds().get(1));

        assertThat(expect(409, confirm(customer, other, "same-key")).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void confirmingWithoutAnIdempotencyKeyIsRejected() throws Exception {
        ShowFixture show = createShow();
        String customer = customer();
        String hold = holdReference(customer, show.showId(), show.seatIds().get(0));

        Response response = send(MockMvcRequestBuilders.post("/api/bookings"), customer,
                Map.of("holdReference", hold, "paymentMethod", "CARD"));

        assertThat(expect(400, response).code()).isEqualTo("MISSING_HEADER");
    }

    @Test
    void aDeclinedPaymentBooksNothingKeepsTheHoldAndCanBeRetried() throws Exception {
        ShowFixture show = createShow();
        long seat = show.seatIds().get(0);
        String customer = customer();
        String hold = holdReference(customer, show.showId(), seat);

        assertThat(expect(402, confirm(customer, hold, "attempt-1", "DECLINE")).code()).isEqualTo("PAYMENT_DECLINED");

        assertThat(seatStatus(show.showId(), seat)).isEqualTo("HELD");
        assertThat(get(customer, "/api/bookings").body().get("totalElements").asInt()).isZero();
        expect(201, confirm(customer, hold, "attempt-2"));
        assertThat(seatStatus(show.showId(), seat)).isEqualTo("BOOKED");
    }

    @Test
    void aCustomerCannotSeeOrUseAnotherCustomersHoldOrBooking() throws Exception {
        ShowFixture show = createShow();
        String owner = customer();
        String intruder = customer();
        String hold = holdReference(owner, show.showId(), show.seatIds().get(0));

        expect(404, get(intruder, "/api/holds/" + hold));
        expect(404, confirm(intruder, hold, "steal"));
        expect(404, delete(intruder, "/api/holds/" + hold));

        String reference = expect(201, confirm(owner, hold, "mine")).body().get("reference").asText();
        expect(404, get(intruder, "/api/bookings/" + reference));
        expect(404, get(intruder, "/api/bookings/" + reference + "/qr"));
        expect(404, post(intruder, "/api/bookings/" + reference + "/cancel", null));
        expect(200, get(owner, "/api/bookings/" + reference));
    }

    @Test
    void aHoldRejectsUnknownSeatsTooManySeatsAndEmptySelections() throws Exception {
        ShowFixture show = createShow(Duration.ofDays(3), 12);
        String customer = customer();

        assertThat(expect(400, hold(customer, show.showId(), -1L)).code()).isEqualTo("INVALID_SEAT");
        assertThat(expect(400, post(customer, "/api/holds", Map.of("showId", show.showId(), "seatIds", List.of()))).code())
                .isEqualTo("VALIDATION_FAILED");
        Long[] eleven = show.seatIds().subList(0, 11).toArray(Long[]::new);
        assertThat(expect(400, hold(customer, show.showId(), eleven)).code()).isEqualTo("TOO_MANY_SEATS");
        expect(404, hold(customer, 999_999_999L, show.seatIds().get(0)));
    }

    @Test
    void shutOutShowsCannotBeHeld() throws Exception {
        ShowFixture show = createShow(Duration.ofHours(1), 4);
        clock.advance(Duration.ofHours(2));
        assertThat(expect(409, hold(customer(), show.showId(), show.seatIds().get(0))).code())
                .isEqualTo("SHOW_NOT_BOOKABLE");
    }

    @Test
    void aNewSelectionReplacesTheCustomersPreviousHoldForThatShow() throws Exception {
        ShowFixture show = createShow();
        long firstSeat = show.seatIds().get(0);
        long secondSeat = show.seatIds().get(1);
        String customer = customer();
        String first = holdReference(customer, show.showId(), firstSeat);

        String second = holdReference(customer, show.showId(), secondSeat);

        assertThat(seatStatus(show.showId(), firstSeat)).isEqualTo("AVAILABLE");
        assertThat(seatStatus(show.showId(), secondSeat)).isEqualTo("HELD");
        assertThat(expect(409, confirm(customer, first, "old")).code()).isEqualTo("HOLD_EXPIRED");
        assertThat(expect(200, get(customer, "/api/holds/active?showId=" + show.showId())).body().get("reference").asText())
                .isEqualTo(second);
    }

    @Test
    void aFailedReselectionKeepsThePreviousHold() throws Exception {
        ShowFixture show = createShow();
        long mine = show.seatIds().get(0);
        long taken = show.seatIds().get(1);
        String customer = customer();
        String original = holdReference(customer, show.showId(), mine);
        holdReference(customer(), show.showId(), taken);

        expect(409, hold(customer, show.showId(), taken));

        assertThat(seatStatus(show.showId(), mine)).isEqualTo("HELD");
        expect(201, confirm(customer, original, "still-mine"));
    }

    @Test
    void releasingAHoldFreesItsSeatsAndCannotBeRepeated() throws Exception {
        ShowFixture show = createShow();
        long seat = show.seatIds().get(0);
        String customer = customer();
        String hold = holdReference(customer, show.showId(), seat);

        expect(204, delete(customer, "/api/holds/" + hold));

        assertThat(seatStatus(show.showId(), seat)).isEqualTo("AVAILABLE");
        assertThat(expect(409, delete(customer, "/api/holds/" + hold)).code()).isEqualTo("HOLD_NOT_ACTIVE");
        assertThat(expect(204, get(customer, "/api/holds/active?showId=" + show.showId())).status()).isEqualTo(204);
    }

    @Test
    void cancellingEarlyRefundsInFullAndFreesTheSeatForResale() throws Exception {
        ShowFixture show = createShow(Duration.ofHours(48), 4);
        long seat = show.seatIds().get(0);
        String customer = customer();
        String reference = book(customer, show.showId(), seat).get("reference").asText();

        JsonNode quote = expect(200, get(customer, "/api/bookings/" + reference + "/cancellation-quote")).body();
        assertThat(quote.get("cancellable").asBoolean()).isTrue();
        assertThat(quote.get("refundPercent").asInt()).isEqualTo(100);

        JsonNode cancelled = expect(200, post(customer, "/api/bookings/" + reference + "/cancel", null)).body();
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("ticketToken").isNull()).isTrue();
        assertThat(cancelled.get("payment").get("refundStatus").asText()).isEqualTo("SIMULATED_REFUNDED");
        assertThat(cancelled.get("payment").get("refundAmount").decimalValue()).isEqualByComparingTo("100.00");

        assertThat(seatStatus(show.showId(), seat)).isEqualTo("AVAILABLE");
        book(customer(), show.showId(), seat);
        assertThat(seatStatus(show.showId(), seat)).isEqualTo("BOOKED");
    }

    @Test
    void cancellingCloserToTheShowRefundsHalf() throws Exception {
        ShowFixture show = createShow(Duration.ofHours(10), 4);
        String customer = customer();
        String reference = book(customer, show.showId(), show.seatIds().get(0)).get("reference").asText();

        JsonNode cancelled = expect(200, post(customer, "/api/bookings/" + reference + "/cancel", null)).body();

        assertThat(cancelled.get("payment").get("refundPercent").asInt()).isEqualTo(50);
        assertThat(cancelled.get("payment").get("refundAmount").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    void cancellationClosesInsideTheWindowAndAfterTheShowStarts() throws Exception {
        ShowFixture show = createShow(Duration.ofHours(10), 4);
        long seat = show.seatIds().get(0);
        String customer = customer();
        String reference = book(customer, show.showId(), seat).get("reference").asText();

        clock.advance(Duration.ofHours(8));
        assertThat(expect(409, post(customer, "/api/bookings/" + reference + "/cancel", null)).code())
                .isEqualTo("CANCELLATION_WINDOW_CLOSED");
        assertThat(get(customer, "/api/bookings/" + reference + "/cancellation-quote").body().get("cancellable").asBoolean())
                .isFalse();

        clock.advance(Duration.ofHours(3));
        assertThat(expect(409, post(customer, "/api/bookings/" + reference + "/cancel", null)).code())
                .isEqualTo("SHOW_ALREADY_STARTED");
        assertThat(seatStatus(show.showId(), seat)).isEqualTo("BOOKED");
    }

    @Test
    void invalidCancellationTransitionsAreRefused() throws Exception {
        ShowFixture show = createShow();
        String customer = customer();
        String first = book(customer, show.showId(), show.seatIds().get(0)).get("reference").asText();
        expect(200, post(customer, "/api/bookings/" + first + "/cancel", null));
        assertThat(expect(409, post(customer, "/api/bookings/" + first + "/cancel", null)).code())
                .isEqualTo("BOOKING_ALREADY_CANCELLED");

        JsonNode second = book(customer, show.showId(), show.seatIds().get(1));
        expect(200, post(staff(), "/api/staff/tickets/validate", Map.of("code", second.get("ticketToken").asText())));
        assertThat(expect(409, post(customer, "/api/bookings/" + second.get("reference").asText() + "/cancel", null)).code())
                .isEqualTo("TICKET_ALREADY_USED");
    }

    @Test
    void cancellingAShowRefundsEveryBookingInFullAndClosesSales() throws Exception {
        ShowFixture show = createShow(Duration.ofHours(10), 6); // inside the 50% window for customers
        String admin = admin();
        String one = customer();
        String two = customer();
        String first = book(one, show.showId(), show.seatIds().get(0)).get("reference").asText();
        String second = book(two, show.showId(), show.seatIds().get(1), show.seatIds().get(2)).get("reference").asText();
        String heldBy = customer();
        String hold = holdReference(heldBy, show.showId(), show.seatIds().get(3));

        assertThat(expect(200, post(admin, "/api/admin/shows/" + show.showId() + "/cancel", null))
                .body().get("refundedBookings").asInt()).isEqualTo(2);

        JsonNode refunded = get(two, "/api/bookings/" + second).body();
        assertThat(refunded.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(refunded.get("cancellationReason").asText()).isEqualTo("SHOW_CANCELLED");
        assertThat(refunded.get("payment").get("refundPercent").asInt()).isEqualTo(100);
        assertThat(refunded.get("payment").get("refundAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(get(one, "/api/bookings/" + first).body().get("status").asText()).isEqualTo("CANCELLED");

        assertThat(expect(409, confirm(heldBy, hold, "too-late")).code()).isEqualTo("HOLD_EXPIRED");
        assertThat(expect(409, hold(customer(), show.showId(), show.seatIds().get(4))).code()).isEqualTo("SHOW_NOT_BOOKABLE");
        assertThat(expect(409, post(admin, "/api/admin/shows/" + show.showId() + "/cancel", null)).code()).isEqualTo("SHOW_CANCELLED");
    }

    @Test
    void aShowWithAdmittedCustomersCannotBeCancelled() throws Exception {
        ShowFixture show = createShow();
        JsonNode booking = book(customer(), show.showId(), show.seatIds().get(0));
        expect(200, post(staff(), "/api/staff/tickets/validate", Map.of("code", booking.get("ticketToken").asText())));

        assertThat(expect(409, post(admin(), "/api/admin/shows/" + show.showId() + "/cancel", null)).code())
                .isEqualTo("SHOW_HAS_CHECKED_IN_TICKETS");
    }

    @Test
    void bookingHistoryIsPagedAndFilterable() throws Exception {
        ShowFixture show = createShow(Duration.ofDays(3), 10);
        String customer = customer();
        List<String> references = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            references.add(book(customer, show.showId(), show.seatIds().get(i)).get("reference").asText());
        }
        expect(200, post(customer, "/api/bookings/" + references.get(0) + "/cancel", null));

        JsonNode firstPage = expect(200, get(customer, "/api/bookings?size=2&page=0")).body();
        assertThat(firstPage.get("content")).hasSize(2);
        assertThat(firstPage.get("totalElements").asInt()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(get(customer, "/api/bookings?status=CANCELLED").body().get("totalElements").asInt()).isEqualTo(1);
        assertThat(get(customer, "/api/bookings?status=CONFIRMED").body().get("totalElements").asInt()).isEqualTo(2);
    }
}
