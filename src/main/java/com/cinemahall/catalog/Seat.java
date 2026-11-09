package com.cinemahall.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A physical seat in a screen's layout. Per-show state lives in ShowSeat. */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id")
    private Screen screen;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private SeatCategory category;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(nullable = false)
    private boolean active = true;

    protected Seat() {
    }

    public Seat(Screen screen, SeatCategory category, String rowLabel, int seatNumber) {
        this.screen = screen;
        this.category = category;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
    }

    public Long getId() {
        return id;
    }

    public Screen getScreen() {
        return screen;
    }

    public SeatCategory getCategory() {
        return category;
    }

    public void setCategory(SeatCategory category) {
        this.category = category;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String label() {
        return rowLabel + seatNumber;
    }
}
