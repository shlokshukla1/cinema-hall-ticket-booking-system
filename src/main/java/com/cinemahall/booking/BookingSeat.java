package com.cinemahall.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** The seat, category and price as sold. A snapshot, so history stays accurate if the catalogue later changes. */
@Entity
@Table(name = "booking_seats")
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "show_seat_id", nullable = false)
    private Long showSeatId;

    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Column(name = "category_name", nullable = false)
    private String categoryName;

    @Column(nullable = false)
    private BigDecimal price;

    /** Unique in the database while set, so two live bookings can never own the same show seat. */
    @Column(name = "active_show_seat_id", unique = true)
    private Long activeShowSeatId;

    protected BookingSeat() {
    }

    public BookingSeat(Booking booking, Long showSeatId, String seatLabel, String categoryName, BigDecimal price) {
        this.booking = booking;
        this.showSeatId = showSeatId;
        this.seatLabel = seatLabel;
        this.categoryName = categoryName;
        this.price = price;
        this.activeShowSeatId = showSeatId;
    }

    void deactivate() {
        activeShowSeatId = null;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Long getActiveShowSeatId() {
        return activeShowSeatId;
    }
}
