package com.cinemahall.booking;

import com.cinemahall.audit.AuditService;
import com.cinemahall.auth.AuthUser;
import com.cinemahall.auth.User;
import com.cinemahall.auth.UserRepository;
import com.cinemahall.booking.BookingDtos.*;
import com.cinemahall.booking.BookingRepositories.*;
import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.booking.Enums.HoldStatus;
import com.cinemahall.booking.Enums.SimulatedOutcome;
import com.cinemahall.common.ApiException;
import com.cinemahall.common.PageResponse;
import com.cinemahall.show.Show;
import com.cinemahall.show.ShowRepository;
import com.cinemahall.show.ShowSeat;
import com.cinemahall.show.ShowSeatRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BookingService {

    /** Crockford-style alphabet with no 0/O or 1/I/L, so a reference survives being read aloud. */
    private static final String REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int REFERENCE_LENGTH = 8;
    private static final int MAX_IDEMPOTENCY_KEY = 100;

    private final BookingRepository bookings;
    private final SeatHoldRepository holds;
    private final PaymentRepository payments;
    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final UserRepository users;
    private final PaymentGateway paymentGateway;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public BookingService(BookingRepository bookings, SeatHoldRepository holds, PaymentRepository payments,
                          ShowRepository shows, ShowSeatRepository showSeats, UserRepository users,
                          PaymentGateway paymentGateway, AuditService audit, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.bookings = bookings;
        this.holds = holds;
        this.payments = payments;
        this.shows = shows;
        this.showSeats = showSeats;
        this.users = users;
        this.paymentGateway = paymentGateway;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record ConfirmResult(BookingView booking, boolean replayed) {
    }

    /**
     * Turns a live hold into a booking. The transaction is managed explicitly so that a declined payment can be
     * audited after the rollback has undone everything else.
     */
    public ConfirmResult confirm(AuthUser user, String idempotencyKey, ConfirmRequest request) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1 to " + MAX_IDEMPOTENCY_KEY + " characters");
        }
        try {
            return transaction.execute(status -> confirmInTransaction(user, idempotencyKey.trim(), request));
        } catch (PaymentGateway.PaymentDeclinedException declined) {
            audit.record(user, "PAYMENT_DECLINED", "SEAT_HOLD", request.holdReference(), "simulated decline");
            throw declined;
        }
    }

    private ConfirmResult confirmInTransaction(AuthUser user, String key, ConfirmRequest request) {
        Instant now = Instant.now(clock);
        long showId = holds.findShowIdByReference(request.holdReference())
                .orElseThrow(() -> ApiException.notFound("Hold"));

        // Lock order for every confirmation: show, then hold, then seats.
        Show show = shows.lockById(showId).orElseThrow(() -> ApiException.notFound("Show"));
        SeatHold hold = holds.lockByReference(request.holdReference())
                .filter(h -> h.getUserId() == user.id()) // someone else's hold looks the same as a missing one
                .orElseThrow(() -> ApiException.notFound("Hold"));

        // Checked after locking, so a concurrent duplicate waits and then sees the committed first attempt.
        var previous = bookings.findByIdempotency(user.id(), key);
        if (previous.isPresent()) {
            if (!previous.get().getHoldId().equals(hold.getId())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different hold");
            }
            return new ConfirmResult(toView(previous.get()), true);
        }

        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            throw ApiException.conflict("HOLD_ALREADY_CONFIRMED", "This hold has already been booked");
        }
        if (!hold.isLiveAt(now)) {
            throw ApiException.conflict("HOLD_EXPIRED", "Your hold has expired. Please select your seats again.");
        }
        if (!show.isBookableAt(now)) {
            throw ApiException.conflict("SHOW_NOT_BOOKABLE", "This show is no longer open for booking");
        }

        List<ShowSeat> heldRows = showSeats.findHeldBy(hold.getId());
        if (heldRows.size() != hold.getSeatCount()) {
            throw ApiException.conflict("HOLD_EXPIRED", "Your hold is no longer valid. Please select your seats again.");
        }

        SimulatedOutcome outcome = request.simulatedOutcome() == null ? SimulatedOutcome.SUCCESS : request.simulatedOutcome();
        String providerRef = paymentGateway.charge(hold.getTotalAmount(), request.paymentMethod(), outcome);

        User customer = users.getReferenceById(user.id());
        Booking booking = new Booking(newReference(), newTicketToken(), customer, show, hold.getId(), key,
                hold.getTotalAmount(), now);
        heldRows.forEach(row -> booking.getSeats().add(new BookingSeat(booking, row.getId(), row.getSeat().label(),
                row.getSeat().getCategory().getName(), row.getPrice())));
        bookings.saveAndFlush(booking); // the UNIQUE on active_show_seat_id is checked here

        if (showSeats.bookHeldSeats(hold.getId(), booking.getId(), now) != hold.getSeatCount()) {
            throw ApiException.conflict("SEATS_UNAVAILABLE", "One of your seats is no longer available");
        }
        payments.save(new Payment(booking.getId(), providerRef, request.paymentMethod(), hold.getTotalAmount(), now));
        hold.setStatus(HoldStatus.CONFIRMED);

        audit.record(user, "BOOKING_CONFIRMED", "BOOKING", booking.getReference(),
                "show=" + showId + " seats=" + heldRows.stream().map(r -> r.getSeat().label())
                        .collect(Collectors.joining(",")) + " amount=" + hold.getTotalAmount());
        return new ConfirmResult(toView(booking), false);
    }

    @Transactional(readOnly = true)
    public BookingView getForCaller(AuthUser caller, String reference) {
        return toView(findVisible(caller, reference));
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingView> listForCustomer(AuthUser caller, BookingStatus status, int page, int size) {
        return search(caller.id(), status, null, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingView> searchAll(BookingStatus status, Long showId, String q, int page, int size) {
        return search(null, status, showId, q, page, size);
    }

    /** Throws 404, not 403, for another customer's booking, so references cannot be probed. */
    Booking findVisible(AuthUser caller, String reference) {
        return bookings.findByReference(reference)
                .filter(b -> caller.isAdmin() || b.getUser().getId() == caller.id())
                .orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private PageResponse<BookingView> search(Long userId, BookingStatus status, Long showId, String q, int page, int size) {
        Specification<Booking> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (userId != null) {
                where.add(cb.equal(root.get("user").get("id"), userId));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (showId != null) {
                where.add(cb.equal(root.get("show").get("id"), showId));
            }
            if (StringUtils.hasText(q)) {
                String needle = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("reference")), needle),
                        cb.like(cb.lower(root.get("user").get("email")), needle)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<Booking> found = bookings.findAll(spec,
                PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        Map<Long, Payment> paymentByBooking = payments
                .findByBookingIdIn(found.getContent().stream().map(Booking::getId).toList()).stream()
                .collect(Collectors.toMap(Payment::getBookingId, Function.identity()));
        return PageResponse.of(found, b -> toView(b, paymentByBooking.get(b.getId())));
    }

    BookingView toView(Booking booking) {
        return toView(booking, payments.findByBookingId(booking.getId()).orElse(null));
    }

    private BookingView toView(Booking booking, Payment payment) {
        Show show = booking.getShow();
        List<SeatLine> seats = booking.getSeats().stream()
                .map(s -> new SeatLine(s.getSeatLabel(), s.getCategoryName(), s.getPrice()))
                .toList();
        // A cancelled ticket has nothing left to present at the door.
        String token = booking.getStatus() == BookingStatus.CONFIRMED ? booking.getTicketToken() : null;
        PaymentView paymentView = payment == null ? null : new PaymentView(payment.getProviderRef(), payment.getMethod(),
                payment.getAmount(), payment.getStatus(), payment.getRefundAmount(), payment.getRefundPercent(),
                payment.getRefundStatus(), payment.getRefundedAt());
        return new BookingView(booking.getReference(), booking.getStatus(), show.getId(), show.getMovie().getId(),
                show.getMovie().getTitle(), show.getScreen().getTheatre().getName(), show.getScreen().getName(),
                show.getScreen().getTheatre().getCity(), show.getStartTime(), show.getEndTime(), show.getLanguage(),
                show.getFormat(), seats, booking.getTotalAmount(), booking.getCreatedAt(), booking.getCancelledAt(),
                booking.getCancellationReason(), booking.getCheckedInAt(), token, paymentView,
                booking.getUser().getEmail());
    }

    private String newReference() {
        StringBuilder reference = new StringBuilder("CH-");
        for (int i = 0; i < REFERENCE_LENGTH; i++) {
            reference.append(REFERENCE_ALPHABET.charAt(random.nextInt(REFERENCE_ALPHABET.length())));
        }
        return reference.toString();
    }

    private String newTicketToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
