package com.cinemahall.booking;

import com.cinemahall.booking.Enums.HoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** A customer's temporary claim on seats. Lifecycle: ACTIVE -> CONFIRMED | RELEASED | EXPIRED. */
@Entity
@Table(name = "seat_holds")
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status = HoldStatus.ACTIVE;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SeatHold() {
    }

    public SeatHold(String reference, Long userId, Long showId, int seatCount, BigDecimal totalAmount,
                    Instant expiresAt, Instant createdAt) {
        this.reference = reference;
        this.userId = userId;
        this.showId = showId;
        this.seatCount = seatCount;
        this.totalAmount = totalAmount;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getShowId() {
        return showId;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public void setStatus(HoldStatus status) {
        this.status = status;
    }

    public int getSeatCount() {
        return seatCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isLiveAt(Instant now) {
        return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
    }
}
