package com.cinemahall.booking;

import com.cinemahall.audit.AuditService;
import com.cinemahall.auth.AuthUser;
import com.cinemahall.booking.BookingDtos.HoldRequest;
import com.cinemahall.booking.BookingDtos.HoldView;
import com.cinemahall.booking.BookingDtos.SeatLine;
import com.cinemahall.booking.BookingRepositories.SeatHoldRepository;
import com.cinemahall.booking.Enums.HoldStatus;
import com.cinemahall.common.ApiException;
import com.cinemahall.show.Show;
import com.cinemahall.show.ShowRepository;
import com.cinemahall.show.ShowSeat;
import com.cinemahall.show.ShowSeatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HoldService {

    private static final int SWEEP_BATCH = 500;

    private final SeatHoldRepository holds;
    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final AuditService audit;
    private final Clock clock;
    private final Duration holdTtl;
    private final int maxSeats;

    public HoldService(SeatHoldRepository holds, ShowRepository shows, ShowSeatRepository showSeats,
                       AuditService audit, Clock clock,
                       @Value("${app.holds.ttl-seconds}") long holdTtlSeconds,
                       @Value("${app.holds.max-seats}") int maxSeats) {
        this.holds = holds;
        this.shows = shows;
        this.showSeats = showSeats;
        this.audit = audit;
        this.clock = clock;
        this.holdTtl = Duration.ofSeconds(holdTtlSeconds);
        this.maxSeats = maxSeats;
    }

    /**
     * Claims every requested seat or none. A customer has one live hold per show: a new selection replaces the
     * old one, so changing your mind needs no extra step and one person cannot pile up seats.
     */
    @Transactional
    public HoldView create(AuthUser user, HoldRequest request) {
        Instant now = Instant.now(clock);
        // Sorted so that concurrent requests lock seat rows in the same order and cannot deadlock.
        List<Long> seatIds = request.seatIds().stream().distinct().sorted().toList();
        if (seatIds.size() > maxSeats) {
            throw ApiException.badRequest("TOO_MANY_SEATS", "You can hold up to " + maxSeats + " seats at a time");
        }
        Show show = shows.findById(request.showId()).orElseThrow(() -> ApiException.notFound("Show"));
        if (!show.isBookableAt(now)) {
            throw ApiException.conflict("SHOW_NOT_BOOKABLE", "This show is no longer open for booking");
        }
        Map<Long, ShowSeat> bySeatId = showSeats.findByShowAndSeats(show.getId(), seatIds).stream()
                .collect(Collectors.toMap(row -> row.getSeat().getId(), Function.identity()));
        if (bySeatId.size() != seatIds.size()) {
            throw ApiException.badRequest("INVALID_SEAT", "One or more seats do not belong to this show");
        }

        releaseExisting(user, show.getId());

        BigDecimal total = bySeatId.values().stream().map(ShowSeat::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant expiresAt = now.plus(holdTtl);
        SeatHold hold = holds.save(new SeatHold(UUID.randomUUID().toString(), user.id(), show.getId(), seatIds.size(),
                total, expiresAt, now));
        for (Long seatId : seatIds) {
            if (showSeats.holdIfFree(show.getId(), seatId, hold.getId(), expiresAt, now) != 1) {
                // Rolls back the whole transaction, including seats already claimed in this loop.
                throw ApiException.conflict("SEATS_UNAVAILABLE",
                        "Seat " + bySeatId.get(seatId).getSeat().label() + " was just taken. Please choose again.");
            }
        }
        List<ShowSeat> heldRows = seatIds.stream().map(bySeatId::get).toList();
        audit.record(user, "SEATS_HELD", "SEAT_HOLD", hold.getReference(),
                "show=" + show.getId() + " seats=" + labels(heldRows) + " until=" + expiresAt);
        return view(hold, heldRows, now);
    }

    @Transactional(readOnly = true)
    public HoldView get(AuthUser user, String reference) {
        SeatHold hold = holds.findByReference(reference).filter(h -> h.getUserId() == user.id())
                .orElseThrow(() -> ApiException.notFound("Hold"));
        return view(hold, showSeats.findHeldBy(hold.getId()), Instant.now(clock));
    }

    /** Lets the seat page resume after a refresh, with its countdown intact. */
    @Transactional(readOnly = true)
    public Optional<HoldView> findLive(AuthUser user, long showId) {
        Instant now = Instant.now(clock);
        return holds.findActive(user.id(), showId).stream()
                .filter(hold -> hold.isLiveAt(now))
                .findFirst()
                .map(hold -> view(hold, showSeats.findHeldBy(hold.getId()), now));
    }

    @Transactional
    public void release(AuthUser user, String reference) {
        SeatHold hold = holds.lockByReference(reference).filter(h -> h.getUserId() == user.id())
                .orElseThrow(() -> ApiException.notFound("Hold"));
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "This hold has already ended");
        }
        showSeats.releaseHeldSeats(List.of(hold.getId()));
        hold.setStatus(HoldStatus.RELEASED);
        audit.record(user, "HOLD_RELEASED", "SEAT_HOLD", reference, "show=" + hold.getShowId());
    }

    /**
     * Housekeeping. Expiry is already enforced by timestamp in every query, so a late sweep never lets a stale
     * hold win; this only tidies the stored state. Holds are updated before seats, the same order as confirmation.
     */
    @Transactional
    public int expireOverdue() {
        Instant now = Instant.now(clock);
        List<Long> overdue = holds.findOverdueIds(now, PageRequest.of(0, SWEEP_BATCH));
        if (overdue.isEmpty()) {
            return 0;
        }
        int expired = holds.markExpired(overdue);
        int seats = showSeats.releaseHeldSeats(overdue);
        audit.record(null, "HOLDS_EXPIRED", "SEAT_HOLD", "batch", expired + " holds expired, " + seats + " seats released");
        return expired;
    }

    private void releaseExisting(AuthUser user, long showId) {
        List<SeatHold> existing = holds.findActive(user.id(), showId);
        if (existing.isEmpty()) {
            return;
        }
        showSeats.releaseHeldSeats(existing.stream().map(SeatHold::getId).toList());
        existing.forEach(hold -> hold.setStatus(HoldStatus.RELEASED));
    }

    private static String labels(List<ShowSeat> rows) {
        return rows.stream().map(row -> row.getSeat().label()).collect(Collectors.joining(","));
    }

    private static HoldView view(SeatHold hold, List<ShowSeat> rows, Instant now) {
        List<ShowSeat> ordered = rows.stream()
                .sorted(Comparator.comparing(row -> row.getSeat().getId()))
                .toList();
        List<SeatLine> lines = ordered.stream().map(row -> new SeatLine(row.getSeat().label(),
                row.getSeat().getCategory().getName(), row.getPrice())).toList();
        List<Long> ids = ordered.stream().map(row -> row.getSeat().getId()).toList();
        return new HoldView(hold.getReference(), hold.getShowId(), hold.getStatus(), lines, ids,
                hold.getTotalAmount(), hold.getExpiresAt(), now);
    }
}
