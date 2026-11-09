package com.cinemahall.booking;

import com.cinemahall.auth.User;
import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.show.Show;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short and human-readable, for customers and the box office. */
    @Column(nullable = false, unique = true)
    private String reference;

    /** Long and unguessable: what the QR code contains. */
    @Column(name = "ticket_token", nullable = false, unique = true)
    private String ticketToken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id")
    private Show show;

    @Column(name = "hold_id", nullable = false, unique = true)
    private Long holdId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "checked_in_by")
    private Long checkedInBy;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<BookingSeat> seats = new ArrayList<>();

    protected Booking() {
    }

    public Booking(String reference, String ticketToken, User user, Show show, Long holdId, String idempotencyKey,
                   BigDecimal totalAmount, Instant createdAt) {
        this.reference = reference;
        this.ticketToken = ticketToken;
        this.user = user;
        this.show = show;
        this.holdId = holdId;
        this.idempotencyKey = idempotencyKey;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
    }

    public void cancel(Instant at, String reason) {
        status = BookingStatus.CANCELLED;
        cancelledAt = at;
        cancellationReason = reason;
        seats.forEach(BookingSeat::deactivate);
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getTicketToken() {
        return ticketToken;
    }

    public User getUser() {
        return user;
    }

    public Show getShow() {
        return show;
    }

    public Long getHoldId() {
        return holdId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public List<BookingSeat> getSeats() {
        return seats;
    }
}
