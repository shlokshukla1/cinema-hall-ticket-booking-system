package com.cinemahall.show;

import com.cinemahall.catalog.Movie;
import com.cinemahall.catalog.Screen;
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

import java.time.Instant;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id")
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id")
    private Screen screen;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private String language;

    @Column(nullable = false)
    private String format;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowStatus status = ShowStatus.SCHEDULED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Show() {
    }

    public Show(Movie movie, Screen screen, Instant startTime, Instant endTime, String language, String format,
                Instant createdAt) {
        this.movie = movie;
        this.screen = screen;
        this.startTime = startTime;
        this.endTime = endTime;
        this.language = language;
        this.format = format;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Movie getMovie() {
        return movie;
    }

    public Screen getScreen() {
        return screen;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public ShowStatus getStatus() {
        return status;
    }

    public void setStatus(ShowStatus status) {
        this.status = status;
    }

    /** True while seats may still be held or confirmed. */
    public boolean isBookableAt(Instant now) {
        return status == ShowStatus.SCHEDULED && startTime.isAfter(now);
    }
}
