package com.cinemahall.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** "At least minHoursBefore hours before the show, refund refundPercent." Editable by admins. */
@Entity
@Table(name = "cancellation_rules")
public class CancellationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "min_hours_before", nullable = false, unique = true)
    private int minHoursBefore;

    @Column(name = "refund_percent", nullable = false)
    private int refundPercent;

    protected CancellationRule() {
    }

    public CancellationRule(int minHoursBefore, int refundPercent) {
        this.minHoursBefore = minHoursBefore;
        this.refundPercent = refundPercent;
    }

    public int getMinHoursBefore() {
        return minHoursBefore;
    }

    public int getRefundPercent() {
        return refundPercent;
    }
}
