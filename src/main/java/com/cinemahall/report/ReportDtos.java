package com.cinemahall.report;

import com.cinemahall.show.ShowStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * bookedSeats comes from seat state and activeTicketSeats from sold tickets. They are counted independently,
     * and "reconciled" says that they agree.
     */
    public record OccupancyRow(Long showId, String movieTitle, String theatreName, String screenName,
                               Instant startTime, ShowStatus status, long totalSeats, long bookedSeats,
                               long heldSeats, long availableSeats, BigDecimal occupancyPercent,
                               long confirmedBookings, long cancelledBookings, long activeTicketSeats,
                               long cancelledTicketSeats, BigDecimal grossSales, BigDecimal refunds,
                               BigDecimal netRevenue, boolean reconciled) {
    }

    public record DailySales(LocalDate date, BigDecimal grossSales, BigDecimal refunds, BigDecimal netRevenue,
                             long bookings) {
    }

    public record MovieSales(String movieTitle, long bookings, long seatsSold, BigDecimal netRevenue) {
    }

    public record SalesSummary(LocalDate fromDate, LocalDate toDate, String timezone, BigDecimal grossSales,
                               BigDecimal refunds, BigDecimal netRevenue, long confirmedBookings,
                               long cancelledBookings, long seatsSold, BigDecimal upcomingOccupancyPercent,
                               List<DailySales> daily, List<MovieSales> movies) {
    }
}
