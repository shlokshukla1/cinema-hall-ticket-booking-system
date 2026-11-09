package com.cinemahall.report;

import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.catalog.Movie;
import com.cinemahall.catalog.Screen;
import com.cinemahall.common.ApiException;
import com.cinemahall.common.PageResponse;
import com.cinemahall.report.ReportDtos.*;
import com.cinemahall.show.Show;
import com.cinemahall.show.ShowRepository;
import com.cinemahall.show.ShowSeatRepository;
import com.cinemahall.show.ShowService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only reporting. Every figure is computed from the live tables, so reports cannot drift from bookings. */
@Service
public class ReportService {

    private static final int MAX_RANGE_DAYS = 92;

    private final EntityManager entityManager;
    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final Clock clock;
    private final ZoneId zone;

    public ReportService(EntityManager entityManager, ShowRepository shows, ShowSeatRepository showSeats, Clock clock,
                         @Value("${app.reporting.timezone}") String timezone) {
        this.entityManager = entityManager;
        this.shows = shows;
        this.showSeats = showSeats;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
    }

    private record BookingTotals(long confirmed, long cancelled, BigDecimal gross, BigDecimal refunds) {
    }

    private record TicketTotals(long active, long cancelled) {
    }

    @Transactional(readOnly = true)
    public PageResponse<OccupancyRow> occupancy(ShowService.ShowFilter filter, int page, int size) {
        Page<Show> found = shows.findAll(ShowService.specification(filter),
                PageResponse.request(page, size, Sort.by(Sort.Direction.ASC, "startTime")));
        List<Long> ids = found.getContent().stream().map(Show::getId).toList();
        if (ids.isEmpty()) {
            return PageResponse.of(found, show -> null);
        }
        Instant now = Instant.now(clock);
        Map<Long, ShowSeatRepository.SeatCounts> seats = showSeats.countByShow(ids, now).stream()
                .collect(Collectors.toMap(ShowSeatRepository.SeatCounts::getShowId, Function.identity()));
        Map<Long, BookingTotals> bookings = bookingTotals(ids);
        Map<Long, TicketTotals> tickets = ticketTotals(ids);

        return PageResponse.of(found, show -> {
            ShowSeatRepository.SeatCounts s = seats.get(show.getId());
            BookingTotals b = bookings.getOrDefault(show.getId(),
                    new BookingTotals(0, 0, BigDecimal.ZERO, BigDecimal.ZERO));
            TicketTotals t = tickets.getOrDefault(show.getId(), new TicketTotals(0, 0));
            long total = s == null ? 0 : s.getTotal();
            long booked = s == null ? 0 : s.getBooked();
            Movie movie = show.getMovie();
            Screen screen = show.getScreen();
            return new OccupancyRow(show.getId(), movie.getTitle(), screen.getTheatre().getName(), screen.getName(),
                    show.getStartTime(), show.getStatus(), total, booked, s == null ? 0 : s.getHeld(),
                    s == null ? 0 : s.getAvailable(), percent(booked, total), b.confirmed(), b.cancelled(),
                    t.active(), t.cancelled(), b.gross(), b.refunds(), b.gross().subtract(b.refunds()),
                    booked == t.active());
        });
    }

    /** Sales are grouped by purchase date in the reporting timezone; a refund counts against its original sale. */
    @Transactional(readOnly = true)
    public SalesSummary sales(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate to = toDate == null ? today : toDate;
        LocalDate from = fromDate == null ? to.minusDays(13) : fromDate;
        if (from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_RANGE", "The start date must not be after the end date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("RANGE_TOO_LARGE", "Choose a range of at most " + MAX_RANGE_DAYS + " days");
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<Tuple> rows = entityManager.createQuery("""
                        select p.amount as amount, p.refundAmount as refund, p.createdAt as createdAt,
                               b.status as status, m.title as title,
                               (select count(bs) from BookingSeat bs
                                where bs.booking = b and bs.activeShowSeatId is not null) as seats
                        from Payment p, Booking b join b.show s join s.movie m
                        where p.bookingId = b.id and p.createdAt >= :start and p.createdAt < :end""", Tuple.class)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        long confirmed = 0;
        long cancelled = 0;
        long seatsSold = 0;
        Map<LocalDate, MutableDay> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d, new MutableDay());
        }
        Map<String, MutableMovie> movies = new HashMap<>();
        for (Tuple row : rows) {
            BigDecimal amount = row.get("amount", BigDecimal.class);
            BigDecimal refund = row.get("refund", BigDecimal.class);
            long seats = row.get("seats", Long.class);
            boolean isConfirmed = row.get("status", BookingStatus.class) == BookingStatus.CONFIRMED;
            gross = gross.add(amount);
            refunds = refunds.add(refund);
            seatsSold += seats;
            if (isConfirmed) {
                confirmed++;
            } else {
                cancelled++;
            }
            MutableDay day = days.get(row.get("createdAt", Instant.class).atZone(zone).toLocalDate());
            day.gross = day.gross.add(amount);
            day.refunds = day.refunds.add(refund);
            day.bookings++;
            MutableMovie movie = movies.computeIfAbsent(row.get("title", String.class), t -> new MutableMovie());
            movie.bookings++;
            movie.seats += seats;
            movie.net = movie.net.add(amount.subtract(refund));
        }
        List<DailySales> daily = new ArrayList<>();
        days.forEach((date, day) -> daily.add(new DailySales(date, day.gross, day.refunds,
                day.gross.subtract(day.refunds), day.bookings)));
        List<MovieSales> movieSales = movies.entrySet().stream()
                .map(e -> new MovieSales(e.getKey(), e.getValue().bookings, e.getValue().seats, e.getValue().net))
                .sorted(Comparator.comparing(MovieSales::netRevenue).reversed().thenComparing(MovieSales::movieTitle))
                .toList();
        return new SalesSummary(from, to, zone.getId(), gross, refunds, gross.subtract(refunds), confirmed, cancelled,
                seatsSold, upcomingOccupancy(), daily, movieSales);
    }

    private static final class MutableDay {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        long bookings;
    }

    private static final class MutableMovie {
        long bookings;
        long seats;
        BigDecimal net = BigDecimal.ZERO;
    }

    private BigDecimal upcomingOccupancy() {
        Tuple totals = entityManager.createQuery("""
                        select count(ss) as total,
                               coalesce(sum(case when ss.status = com.cinemahall.show.ShowSeatStatus.BOOKED
                                                 then 1 else 0 end), 0) as booked
                        from ShowSeat ss join ss.show s
                        where s.status = com.cinemahall.show.ShowStatus.SCHEDULED and s.startTime > :now""", Tuple.class)
                .setParameter("now", Instant.now(clock))
                .getSingleResult();
        return percent(((Number) totals.get("booked")).longValue(), ((Number) totals.get("total")).longValue());
    }

    private Map<Long, BookingTotals> bookingTotals(List<Long> showIds) {
        return entityManager.createQuery("""
                        select b.show.id as showId,
                               sum(case when b.status = com.cinemahall.booking.Enums.BookingStatus.CONFIRMED then 1 else 0 end) as confirmed,
                               sum(case when b.status = com.cinemahall.booking.Enums.BookingStatus.CANCELLED then 1 else 0 end) as cancelled,
                               sum(p.amount) as gross,
                               sum(p.refundAmount) as refunds
                        from Booking b, Payment p
                        where p.bookingId = b.id and b.show.id in :ids
                        group by b.show.id""", Tuple.class)
                .setParameter("ids", showIds)
                .getResultList().stream()
                .collect(Collectors.toMap(t -> t.get("showId", Long.class), t -> new BookingTotals(
                        ((Number) t.get("confirmed")).longValue(), ((Number) t.get("cancelled")).longValue(),
                        t.get("gross", BigDecimal.class), t.get("refunds", BigDecimal.class))));
    }

    private Map<Long, TicketTotals> ticketTotals(List<Long> showIds) {
        return entityManager.createQuery("""
                        select b.show.id as showId,
                               sum(case when bs.activeShowSeatId is not null then 1 else 0 end) as active,
                               sum(case when bs.activeShowSeatId is null then 1 else 0 end) as cancelled
                        from BookingSeat bs join bs.booking b
                        where b.show.id in :ids
                        group by b.show.id""", Tuple.class)
                .setParameter("ids", showIds)
                .getResultList().stream()
                .collect(Collectors.toMap(t -> t.get("showId", Long.class), t -> new TicketTotals(
                        ((Number) t.get("active")).longValue(), ((Number) t.get("cancelled")).longValue())));
    }

    private static BigDecimal percent(long part, long whole) {
        if (whole == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }
}
