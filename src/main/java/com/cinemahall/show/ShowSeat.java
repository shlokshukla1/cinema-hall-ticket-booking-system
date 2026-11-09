package com.cinemahall.show;

import com.cinemahall.catalog.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One sellable seat at one show, and the single source of truth for its state:
 * AVAILABLE -> HELD -> BOOKED -> AVAILABLE (after cancellation), or HELD -> AVAILABLE (released or expired).
 * State changes go through conditional UPDATEs in ShowSeatRepository, never through entity setters.
 */
@Entity
@Table(name = "show_seats")
public class ShowSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id")
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    /** Price fixed when the show is scheduled, so later category price changes never alter this show. */
    @Column(nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowSeatStatus status = ShowSeatStatus.AVAILABLE;

    @Column(name = "hold_id")
    private Long holdId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "booking_id")
    private Long bookingId;

    protected ShowSeat() {
    }

    public ShowSeat(Show show, Seat seat, BigDecimal price) {
        this.show = show;
        this.seat = seat;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public Show getShow() {
        return show;
    }

    public Seat getSeat() {
        return seat;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public ShowSeatStatus getStatus() {
        return status;
    }

    public Long getHoldId() {
        return holdId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Long getBookingId() {
        return bookingId;
    }

    /** What customers should see: a hold past its expiry is already free, even before the sweeper runs. */
    public ShowSeatStatus effectiveStatus(Instant now) {
        if (status == ShowSeatStatus.HELD && !holdExpiresAt.isAfter(now)) {
            return ShowSeatStatus.AVAILABLE;
        }
        return status;
    }
}
