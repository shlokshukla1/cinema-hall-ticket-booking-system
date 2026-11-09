package com.cinemahall;

import com.cinemahall.auth.Role;
import com.cinemahall.booking.HoldService;
import com.cinemahall.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The five acceptance tests from the assessment brief, in the brief's order. */
class AcceptanceTest extends ApiTestBase {

    private static final int THREADS = 12;

    @Autowired
    private HoldService holdService;
    @Autowired
    private JdbcTemplate jdbc;

    /** Releases all tasks at the same moment, to maximise real contention. */
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Nested
    @DisplayName("AT1: an available seat can be held and booked")
    class HoldAndBook {

        @Test
        void seatMovesFromAvailableToHeldToBooked() throws Exception {
            ShowFixture show = createShow();
            long seat = show.seatIds().get(0);
            String customer = customer();
            assertThat(seatStatus(show.showId(), seat)).isEqualTo("AVAILABLE");

            JsonNode hold = expect(201, hold(customer, show.showId(), seat)).body();
            assertThat(hold.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(hold.get("totalAmount").decimalValue()).isEqualByComparingTo("100.00");
            assertThat(seatStatus(show.showId(), seat)).isEqualTo("HELD");

            JsonNode booking = expect(201, confirm(customer, hold.get("reference").asText(), "key-" + seat)).body();
            assertThat(booking.get("status").asText()).isEqualTo("CONFIRMED");
            assertThat(booking.get("reference").asText()).startsWith("CH-");
            assertThat(booking.get("ticketToken").asText()).hasSizeGreaterThan(40);
            assertThat(booking.get("payment").get("status").asText()).isEqualTo("CAPTURED");
            assertThat(seatStatus(show.showId(), seat)).isEqualTo("BOOKED");
        }

        @Test
        void ticketHasAScannableQrImage() throws Exception {
            ShowFixture show = createShow();
            String customer = customer();
            JsonNode booking = book(customer, show.showId(), show.seatIds().get(0));

            Response qr = get(customer, "/api/bookings/" + booking.get("reference").asText() + "/qr");
            assertThat(qr.status()).isEqualTo(200);
            assertThat(qr.raw().getResponse().getContentType()).isEqualTo("image/png");

            // Decode the image: it must really contain the ticket token that the entry gate accepts.
            // PURE_BARCODE reads this exact image. ZXing's camera-style locator misses about 0.8% of random
            // tokens, even on perfect images, which made a plain decode fail intermittently (see README).
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(qr.raw().getResponse().getContentAsByteArray()));
            String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new BufferedImageLuminanceSource(image))), Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE)).getText();
            assertThat(decoded).isEqualTo(booking.get("ticketToken").asText());
            expect(200, post(staff(), "/api/staff/tickets/validate", Map.of("code", decoded)));
        }
    }

    @Nested
    @DisplayName("AT2: the same seat cannot be confirmed to two users")
    class NoDoubleBooking {

        @Test
        void secondCustomerCannotHoldAHeldSeatOrABookedSeat() throws Exception {
            ShowFixture show = createShow();
            long seat = show.seatIds().get(0);
            String first = customer();
            String second = customer();

            String reference = holdReference(first, show.showId(), seat);
            assertThat(expect(409, hold(second, show.showId(), seat)).code()).isEqualTo("SEATS_UNAVAILABLE");

            expect(201, confirm(first, reference, "first"));
            assertThat(expect(409, hold(second, show.showId(), seat)).code()).isEqualTo("SEATS_UNAVAILABLE");
        }

        @Test
        void underRealConcurrencyExactlyOneCustomerWinsTheSeat() throws Exception {
            ShowFixture show = createShow();
            long seat = show.seatIds().get(0);
            List<String> customers = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                customers.add(customer());
            }

            List<Callable<Response>> attempts = customers.stream()
                    .<Callable<Response>>map(token -> () -> hold(token, show.showId(), seat))
                    .toList();
            List<Response> results = runTogether(attempts);

            assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
            assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(THREADS - 1);
        }

        @Test
        void overlappingMultiSeatHoldsNeverClaimPartOfASelection() throws Exception {
            ShowFixture show = createShow(Duration.ofDays(3), 6);
            List<Long> seats = show.seatIds();
            String a = customer();
            String b = customer();
            String c = customer();

            // All three selections want seat index 2, so at most one can succeed.
            List<Response> results = runTogether(List.of(
                    () -> hold(a, show.showId(), seats.get(0), seats.get(1), seats.get(2)),
                    () -> hold(b, show.showId(), seats.get(2), seats.get(3)),
                    () -> hold(c, show.showId(), seats.get(4), seats.get(2))));

            assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
            int claimed = results.stream().filter(r -> r.status() == 201)
                    .mapToInt(r -> r.body().get("seats").size()).sum();
            long heldSeats = seats.stream().filter(id -> {
                try {
                    return seatStatus(show.showId(), id).equals("HELD");
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }).count();
            assertThat(heldSeats).as("losers must leave no seats behind").isEqualTo(claimed);
        }

        @Test
        void concurrentConfirmationsOfOneHoldCreateOneBooking() throws Exception {
            ShowFixture show = createShow();
            long seat = show.seatIds().get(0);
            String customer = customer();
            String reference = holdReference(customer, show.showId(), seat);

            List<Callable<Response>> attempts = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                attempts.add(() -> confirm(customer, reference, UUID.randomUUID().toString()));
            }
            List<Response> results = runTogether(attempts);

            assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
            assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(THREADS - 1);
            assertThat(results.stream().filter(r -> r.status() == 409).map(Response::code))
                    .containsOnly("HOLD_ALREADY_CONFIRMED");
        }

        @Test
        void databaseConstraintRejectsASecondLiveOwnerEvenIfApplicationCodeIsBypassed() throws Exception {
            ShowFixture show = createShow();
            String customer = customer();
            JsonNode booking = book(customer, show.showId(), show.seatIds().get(0));
            long bookingId = jdbc.queryForObject("SELECT id FROM bookings WHERE reference = ?", Long.class,
                    booking.get("reference").asText());
            long showSeatId = jdbc.queryForObject("SELECT show_seat_id FROM booking_seats WHERE booking_id = ?",
                    Long.class, bookingId);
            long otherBookingId = book(customer, show.showId(), show.seatIds().get(1)).isObject()
                    ? jdbc.queryForObject("SELECT MAX(id) FROM bookings", Long.class) : -1;

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO booking_seats (booking_id, show_seat_id, seat_label, category_name, price, active_show_seat_id)
                    VALUES (?, ?, 'A1', 'X', 1.00, ?)""", otherBookingId, showSeatId, showSeatId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void databaseConstraintRejectsAnImpossibleSeatState() throws Exception {
            ShowFixture show = createShow();
            long seat = show.seatIds().get(0);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE show_seats SET status = 'BOOKED' WHERE show_id = ? AND seat_id = ?", show.showId(), seat))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("AT3: expired holds release the seat; confirmed bookings remain unavailable")
    class HoldExpiry {

        @Test
        void anExpiredHoldCannotBeConfirmed() throws Exception {
            ShowFixture show = createShow();
            long seat = show.seatIds().get(0);
            String customer = customer();
            String reference = holdReference(customer, show.showId(), seat);

            clock.advance(Duration.ofSeconds(301));

            assertThat(expect(409, confirm(customer, reference, "late")).code()).isEqualTo("HOLD_EXPIRED");
        }

        @Test
        void aHoldStillWorksJustBeforeExpiry() throws Exception {
            ShowFixture show = createShow();
            String customer = customer();
            String reference = holdReference(customer, show.showId(), show.seatIds().get(0));

            clock.advance(Duration.ofSeconds(295));

            expect(201, confirm(customer, reference, "in-time"));
        }

        @Test
        void anExpiredSeatIsFreeImmediatelyEvenBeforeTheSweeperRuns() throws Exception {
            ShowFixture show = createShow();
            long seat = show.seatIds().get(0);
            String first = customer();
            String second = customer();
            String firstHold = holdReference(first, show.showId(), seat);

            clock.advance(Duration.ofSeconds(301));

            assertThat(seatStatus(show.showId(), seat)).isEqualTo("AVAILABLE");
            String secondHold = holdReference(second, show.showId(), seat);
            expect(201, confirm(second, secondHold, "second-wins"));
            // The first customer's old hold must not be able to take the seat back.
            assertThat(expect(409, confirm(first, firstHold, "first-late")).code()).isEqualTo("HOLD_EXPIRED");
            assertThat(seatStatus(show.showId(), seat)).isEqualTo("BOOKED");
        }

        @Test
        void sweeperReleasesExpiredHoldsButNeverConfirmedBookings() throws Exception {
            ShowFixture show = createShow();
            long booked = show.seatIds().get(0);
            long abandoned = show.seatIds().get(1);
            String buyer = customer();
            String browser = customer();
            book(buyer, show.showId(), booked);
            String abandonedHold = holdReference(browser, show.showId(), abandoned);

            clock.advance(Duration.ofSeconds(301));
            assertThat(holdService.expireOverdue()).isPositive();

            assertThat(jdbc.queryForObject("SELECT status FROM seat_holds WHERE reference = ?", String.class, abandonedHold))
                    .isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                    String.class, show.showId(), abandoned)).isEqualTo("AVAILABLE");
            assertThat(seatStatus(show.showId(), booked)).isEqualTo("BOOKED");
            assertThat(expect(409, hold(customer(), show.showId(), booked)).code()).isEqualTo("SEATS_UNAVAILABLE");
        }
    }

    @Nested
    @DisplayName("AT4: a ticket reference or QR can be validated once")
    class TicketValidation {

        private Response validate(String token, String code) throws Exception {
            return post(token, "/api/staff/tickets/validate", Map.of("code", code));
        }

        @Test
        void qrTokenAdmitsOnceAndIsRejectedAfterwards() throws Exception {
            ShowFixture show = createShow();
            JsonNode booking = book(customer(), show.showId(), show.seatIds().get(0));
            String staff = staff();

            JsonNode admitted = expect(200, validate(staff, booking.get("ticketToken").asText())).body();
            assertThat(admitted.get("valid").asBoolean()).isTrue();
            assertThat(admitted.get("seats").get(0).asText()).isEqualTo("A1");

            assertThat(expect(409, validate(staff, booking.get("ticketToken").asText())).code())
                    .isEqualTo("TICKET_ALREADY_USED");
        }

        @Test
        void printedReferenceWorksAndSharesTheSameSingleUse() throws Exception {
            ShowFixture show = createShow();
            JsonNode booking = book(customer(), show.showId(), show.seatIds().get(0));
            String staff = staff();

            expect(200, validate(staff, booking.get("reference").asText().toLowerCase()));
            assertThat(expect(409, validate(staff, booking.get("ticketToken").asText())).code())
                    .isEqualTo("TICKET_ALREADY_USED");
        }

        @Test
        void simultaneousScansAdmitExactlyOnce() throws Exception {
            ShowFixture show = createShow();
            String token = book(customer(), show.showId(), show.seatIds().get(0)).get("ticketToken").asText();
            String staff = staff();

            List<Callable<Response>> scans = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                scans.add(() -> validate(staff, token));
            }
            List<Response> results = runTogether(scans);

            assertThat(results.stream().filter(r -> r.status() == 200)).hasSize(1);
            assertThat(results.stream().filter(r -> r.status() == 409).map(Response::code))
                    .hasSize(THREADS - 1).containsOnly("TICKET_ALREADY_USED");
        }

        @Test
        void unknownCancelledAndFinishedTicketsAreRejected() throws Exception {
            String staff = staff();
            assertThat(expect(404, validate(staff, "NOT-A-TICKET")).code()).isEqualTo("TICKET_NOT_FOUND");

            ShowFixture show = createShow();
            String customer = customer();
            JsonNode cancelled = book(customer, show.showId(), show.seatIds().get(0));
            JsonNode finished = book(customer, show.showId(), show.seatIds().get(1));
            expect(200, post(customer, "/api/bookings/" + cancelled.get("reference").asText() + "/cancel", null));
            assertThat(expect(409, validate(staff, cancelled.get("reference").asText())).code())
                    .isEqualTo("TICKET_CANCELLED");

            clock.advance(Duration.ofDays(4));
            assertThat(expect(409, validate(staff, finished.get("reference").asText())).code())
                    .isEqualTo("TICKET_EXPIRED");
        }

        @Test
        void customersCannotValidateTickets() throws Exception {
            ShowFixture show = createShow();
            String customer = customer();
            JsonNode booking = book(customer, show.showId(), show.seatIds().get(0));
            expect(403, validate(customer, booking.get("ticketToken").asText()));
        }
    }

    @Nested
    @DisplayName("AT5: the admin occupancy report matches bookings and cancellations")
    class OccupancyReport {

        private JsonNode reportRow(String admin, long showId) throws Exception {
            for (JsonNode row : expect(200, get(admin, "/api/admin/reports/occupancy?size=100&from="
                    + clock.instant().minus(Duration.ofDays(1)) + "&to=" + clock.instant().plus(Duration.ofDays(30))))
                    .body().get("content")) {
                if (row.get("showId").asLong() == showId) {
                    return row;
                }
            }
            // The report is paged, so fall back to the last pages when the database holds many shows.
            int pages = get(admin, "/api/admin/reports/occupancy?size=100").body().get("totalPages").asInt();
            for (int page = pages - 1; page >= 0; page--) {
                for (JsonNode row : get(admin, "/api/admin/reports/occupancy?size=100&page=" + page).body().get("content")) {
                    if (row.get("showId").asLong() == showId) {
                        return row;
                    }
                }
            }
            throw new AssertionError("Show " + showId + " is missing from the report");
        }

        @Test
        void reportTracksBookingsHoldsCancellationsAndRefunds() throws Exception {
            ShowFixture show = createShow(Duration.ofDays(3), 10);
            List<Long> seats = show.seatIds();
            String admin = tokenFor(Role.ADMIN);
            String one = customer();
            String two = customer();

            JsonNode empty = reportRow(admin, show.showId());
            assertThat(empty.get("totalSeats").asLong()).isEqualTo(10);
            assertThat(empty.get("bookedSeats").asLong()).isZero();

            // 3 seats and 2 seats booked, then 1 seat held.
            JsonNode keep = book(one, show.showId(), seats.get(0), seats.get(1), seats.get(2));
            JsonNode cancel = book(two, show.showId(), seats.get(3), seats.get(4));
            holdReference(customer(), show.showId(), seats.get(5));

            JsonNode before = reportRow(admin, show.showId());
            assertThat(before.get("bookedSeats").asLong()).isEqualTo(5);
            assertThat(before.get("heldSeats").asLong()).isEqualTo(1);
            assertThat(before.get("availableSeats").asLong()).isEqualTo(4);
            assertThat(before.get("occupancyPercent").decimalValue()).isEqualByComparingTo("50.0");
            assertThat(before.get("confirmedBookings").asLong()).isEqualTo(2);
            assertThat(before.get("grossSales").decimalValue()).isEqualByComparingTo("500.00");
            assertThat(before.get("reconciled").asBoolean()).isTrue();

            // More than 24 hours out, so the default policy refunds the cancelled 200.00 in full.
            expect(200, post(two, "/api/bookings/" + cancel.get("reference").asText() + "/cancel", null));

            JsonNode after = reportRow(admin, show.showId());
            assertThat(after.get("bookedSeats").asLong()).isEqualTo(3);
            assertThat(after.get("activeTicketSeats").asLong()).isEqualTo(3);
            assertThat(after.get("cancelledTicketSeats").asLong()).isEqualTo(2);
            assertThat(after.get("occupancyPercent").decimalValue()).isEqualByComparingTo("30.0");
            assertThat(after.get("confirmedBookings").asLong()).isEqualTo(1);
            assertThat(after.get("cancelledBookings").asLong()).isEqualTo(1);
            assertThat(after.get("grossSales").decimalValue()).isEqualByComparingTo("500.00");
            assertThat(after.get("refunds").decimalValue()).isEqualByComparingTo("200.00");
            assertThat(after.get("netRevenue").decimalValue()).isEqualByComparingTo("300.00");
            assertThat(after.get("reconciled").asBoolean()).isTrue();

            // The figures agree with an independent count of the tables behind them.
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
                    WHERE b.show_id = ? AND b.status = 'CONFIRMED'""", Long.class, show.showId()))
                    .isEqualTo(after.get("bookedSeats").asLong());
            assertThat(keep.get("status").asText()).isEqualTo("CONFIRMED");
        }

        @Test
        void salesSummaryIncludesTheSaleAndItsRefund() throws Exception {
            ShowFixture show = createShow();
            String admin = admin();
            String customer = customer();
            JsonNode before = expect(200, get(admin, "/api/admin/reports/sales")).body();

            JsonNode booking = book(customer, show.showId(), show.seatIds().get(0), show.seatIds().get(1));
            expect(200, post(customer, "/api/bookings/" + booking.get("reference").asText() + "/cancel", null));

            JsonNode after = expect(200, get(admin, "/api/admin/reports/sales")).body();
            assertThat(after.get("grossSales").decimalValue().subtract(before.get("grossSales").decimalValue()))
                    .isEqualByComparingTo("200.00");
            assertThat(after.get("refunds").decimalValue().subtract(before.get("refunds").decimalValue()))
                    .isEqualByComparingTo("200.00");
            assertThat(after.get("cancelledBookings").asLong() - before.get("cancelledBookings").asLong()).isEqualTo(1);
            assertThat(after.get("daily")).hasSize(14);
        }
    }
}
