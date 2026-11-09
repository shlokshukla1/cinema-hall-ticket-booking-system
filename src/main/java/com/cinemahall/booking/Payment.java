package com.cinemahall.booking;

import com.cinemahall.booking.Enums.PaymentMethod;
import com.cinemahall.booking.Enums.PaymentStatus;
import com.cinemahall.booking.Enums.RefundStatus;
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

/** A simulated payment. No card or account details are ever collected or stored. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "provider_ref", nullable = false, unique = true)
    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.CAPTURED;

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(name = "refund_percent", nullable = false)
    private int refundPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false)
    private RefundStatus refundStatus = RefundStatus.NONE;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(Long bookingId, String providerRef, PaymentMethod method, BigDecimal amount, Instant createdAt) {
        this.bookingId = bookingId;
        this.providerRef = providerRef;
        this.method = method;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public void applyRefund(int percent, BigDecimal refund, Instant at) {
        refundPercent = percent;
        refundAmount = refund;
        if (refund.signum() > 0) {
            refundStatus = RefundStatus.SIMULATED_REFUNDED;
            refundedAt = at;
        } else {
            refundStatus = RefundStatus.NOT_ELIGIBLE;
        }
    }

    public Long getBookingId() {
        return bookingId;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public int getRefundPercent() {
        return refundPercent;
    }

    public RefundStatus getRefundStatus() {
        return refundStatus;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
