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

@Entity
@Table(name = "screens")
public class Screen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theatre_id")
    private Theatre theatre;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    protected Screen() {
    }

    public Screen(Theatre theatre, String name) {
        this.theatre = theatre;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Theatre getTheatre() {
        return theatre;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
