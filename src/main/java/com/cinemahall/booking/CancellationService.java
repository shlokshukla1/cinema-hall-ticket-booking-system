package com.cinemahall.booking;

import com.cinemahall.audit.AuditService;
import com.cinemahall.auth.AuthUser;
import com.cinemahall.booking.BookingDtos.*;
import com.cinemahall.booking.BookingRepositories.*;
import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.booking.Enums.HoldStatus;
import com.cinemahall.common.ApiException;
import com.cinemahall.show.Show;
import com.cinemahall.show.ShowRepository;
import com.cinemahall.show.ShowSeatRepository;
import com.cinemahall.show.ShowStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CancellationService {

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final SeatHoldRepository holds;
    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final CancellationRuleRepository rules;
    private final BookingService bookingService;
    private final AuditService audit;
    private final Clock clock;

    public CancellationService(BookingRepository bookings, PaymentRepository payments, SeatHoldRepository holds,
                               ShowRepository shows, ShowSeatRepository showSeats, CancellationRuleRepository rules,
                               BookingService bookingService, AuditService audit, Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.holds = holds;
        this.shows = shows;
        this.showSeats = showSeats;
        this.rules = rules;
        this.bookingService = bookingService;
        this.audit = audit;
        this.clock = clock;
    }

    /** Shown before the customer confirms, computed by the same code that performs the cancellation. */
    @Transactional(readOnly = true)
    public CancellationQuoteView quote(AuthUser caller, String reference) {
        Booking booking = bookingService.findVisible(caller, reference);
        String blocked = blockingReason(booking, Instant.now(clock));
        if (blocked != null) {
            return new CancellationQuoteView(false, blocked, 0, java.math.BigDecimal.ZERO);
        }
        CancellationPolicy.Quote quote = policyQuote(booking);
        return quote.cancellable()
                ? new CancellationQuoteView(true, null, quote.refundPercent(), quote.refundAmount())
                : new CancellationQuoteView(false, "The cancellation window for this show has closed", 0,
                java.math.BigDecimal.ZERO);
    }

    @Transactional
    public BookingView cancel(AuthUser caller, String reference, String reason) {
        // The ownership check reads only the owner id. Loading the entity here would leave a stale copy in the
        // session, and the locking query below would hand back that copy instead of the row it just locked.
        bookings.findOwnerId(reference)
                .filter(ownerId -> caller.isAdmin() || ownerId == caller.id())
                .orElseThrow(() -> ApiException.notFound("Booking"));
        Booking booking = bookings.lockByReference(reference).orElseThrow(() -> ApiException.notFound("Booking"));
        Instant now = Instant.now(clock);

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw ApiException.conflict("BOOKING_ALREADY_CANCELLED", "This booking is already cancelled");
        }
        if (booking.getCheckedInAt() != null) {
            throw ApiException.conflict("TICKET_ALREADY_USED", "This ticket has been used and cannot be cancelled");
        }
        if (!booking.getShow().getStartTime().isAfter(now)) {
            throw ApiException.conflict("SHOW_ALREADY_STARTED", "This show has already started");
        }
        CancellationPolicy.Quote quote = policyQuote(booking);
        if (!quote.cancellable()) {
            throw ApiException.conflict("CANCELLATION_WINDOW_CLOSED", "The cancellation window for this show has closed");
        }
        String recorded = reason == null || reason.isBlank() ? (caller.isAdmin() ? "ADMIN_REQUEST" : "CUSTOMER_REQUEST") : reason.trim();
        applyCancellation(booking, quote.refundPercent(), quote.refundAmount(), recorded, now);
        audit.record(caller, "BOOKING_CANCELLED", "BOOKING", reference,
                "refund=" + quote.refundAmount() + " (" + quote.refundPercent() + "%) reason=" + recorded);
        return bookingService.toView(booking);
    }

    /** Cancelling a show refunds every ticket in full, whatever the customer-facing policy says. */
    @Transactional
    public int cancelShow(AuthUser actor, long showId) {
        Instant now = Instant.now(clock);
        Show show = shows.lockById(showId).orElseThrow(() -> ApiException.notFound("Show"));
        if (show.getStatus() == ShowStatus.CANCELLED) {
            throw ApiException.conflict("SHOW_CANCELLED", "This show is already cancelled");
        }
        if (!show.getStartTime().isAfter(now)) {
            throw ApiException.conflict("SHOW_ALREADY_STARTED", "A show that has started cannot be cancelled");
        }
        List<Booking> confirmed = bookings.lockConfirmedForShow(showId);
        if (confirmed.stream().anyMatch(b -> b.getCheckedInAt() != null)) {
            throw ApiException.conflict("SHOW_HAS_CHECKED_IN_TICKETS", "Customers have already been admitted to this show");
        }
        show.setStatus(ShowStatus.CANCELLED);

        List<SeatHold> activeHolds = holds.findActiveForShow(showId);
        if (!activeHolds.isEmpty()) {
            showSeats.releaseHeldSeats(activeHolds.stream().map(SeatHold::getId).toList());
            activeHolds.forEach(hold -> hold.setStatus(HoldStatus.RELEASED));
        }
        for (Booking booking : confirmed) {
            applyCancellation(booking, 100, booking.getTotalAmount(), "SHOW_CANCELLED", now);
        }
        audit.record(actor, "SHOW_CANCELLED", "SHOW", showId, confirmed.size() + " bookings refunded in full");
        return confirmed.size();
    }

    @Transactional(readOnly = true)
    public List<CancellationRuleView> listRules() {
        return rules.findAllByOrderByMinHoursBeforeDesc().stream()
                .map(rule -> new CancellationRuleView(rule.getMinHoursBefore(), rule.getRefundPercent()))
                .toList();
    }

    @Transactional
    public List<CancellationRuleView> replaceRules(AuthUser actor, CancellationRulesRequest request) {
        Set<Integer> hours = new HashSet<>();
        for (CancellationRuleRequest rule : request.rules()) {
            if (!hours.add(rule.minHoursBefore())) {
                throw ApiException.badRequest("DUPLICATE_RULE", "Each notice period can only appear once");
            }
        }
        rules.deleteAllInBatch();
        rules.saveAll(request.rules().stream()
                .map(rule -> new CancellationRule(rule.minHoursBefore(), rule.refundPercent()))
                .toList());
        audit.record(actor, "CANCELLATION_POLICY_UPDATED", "CANCELLATION_POLICY", "current",
                request.rules().stream().map(r -> r.minHoursBefore() + "h=" + r.refundPercent() + "%").toList().toString());
        return listRules();
    }

    private void applyCancellation(Booking booking, int percent, java.math.BigDecimal refund, String reason, Instant now) {
        int seats = booking.getSeats().size();
        if (showSeats.releaseBookedSeats(booking.getId()) != seats) {
            throw new IllegalStateException("Seat state does not match booking " + booking.getReference());
        }
        booking.cancel(now, reason);
        payments.findByBookingId(booking.getId())
                .orElseThrow(() -> new IllegalStateException("No payment for booking " + booking.getReference()))
                .applyRefund(percent, refund, now);
    }

    private CancellationPolicy.Quote policyQuote(Booking booking) {
        return CancellationPolicy.quote(rules.findAllByOrderByMinHoursBeforeDesc(), Instant.now(clock),
                booking.getShow().getStartTime(), booking.getTotalAmount());
    }

    private static String blockingReason(Booking booking, Instant now) {
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return "This booking is already cancelled";
        }
        if (booking.getCheckedInAt() != null) {
            return "This ticket has been used";
        }
        if (!booking.getShow().getStartTime().isAfter(now)) {
            return "This show has already started";
        }
        return null;
    }
}
