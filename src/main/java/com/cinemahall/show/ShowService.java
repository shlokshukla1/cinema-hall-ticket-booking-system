package com.cinemahall.show;

import com.cinemahall.audit.AuditService;
import com.cinemahall.auth.AuthUser;
import com.cinemahall.catalog.CatalogRepositories.MovieRepository;
import com.cinemahall.catalog.CatalogRepositories.ScreenRepository;
import com.cinemahall.catalog.CatalogRepositories.SeatRepository;
import com.cinemahall.catalog.CatalogService;
import com.cinemahall.catalog.Movie;
import com.cinemahall.catalog.Screen;
import com.cinemahall.catalog.Seat;
import com.cinemahall.common.ApiException;
import com.cinemahall.common.PageResponse;
import com.cinemahall.show.ShowDtos.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ShowService {

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final MovieRepository movies;
    private final ScreenRepository screens;
    private final SeatRepository seats;
    private final AuditService audit;
    private final Clock clock;
    private final Duration cleaningBuffer;

    public ShowService(ShowRepository shows, ShowSeatRepository showSeats, MovieRepository movies,
                       ScreenRepository screens, SeatRepository seats, AuditService audit, Clock clock,
                       @Value("${app.shows.cleaning-buffer-minutes}") long cleaningBufferMinutes) {
        this.shows = shows;
        this.showSeats = showSeats;
        this.movies = movies;
        this.screens = screens;
        this.seats = seats;
        this.audit = audit;
        this.clock = clock;
        this.cleaningBuffer = Duration.ofMinutes(cleaningBufferMinutes);
    }

    public record ShowFilter(Long movieId, Long theatreId, String city, String language, String format,
                             Instant from, Instant to, ShowStatus status) {
    }

    /** Customer view: only shows that can still be booked. */
    @Transactional(readOnly = true)
    public PageResponse<ShowView> searchPublic(ShowFilter filter, int page, int size) {
        Instant now = Instant.now(clock);
        Instant from = filter.from() == null || filter.from().isBefore(now) ? now : filter.from();
        return searchAdmin(new ShowFilter(filter.movieId(), filter.theatreId(), filter.city(), filter.language(),
                filter.format(), from, filter.to(), ShowStatus.SCHEDULED), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ShowView> searchAdmin(ShowFilter filter, int page, int size) {
        Page<Show> found = shows.findAll(specification(filter),
                PageResponse.request(page, size, Sort.by("startTime")));
        Map<Long, ShowSeatRepository.SeatCounts> counts = counts(found.getContent());
        Instant now = Instant.now(clock);
        return PageResponse.of(found, show -> toView(show, counts.get(show.getId()), now));
    }

    public static Specification<Show> specification(ShowFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (filter.movieId() != null) {
                where.add(cb.equal(root.get("movie").get("id"), filter.movieId()));
            }
            if (filter.theatreId() != null) {
                where.add(cb.equal(root.get("screen").get("theatre").get("id"), filter.theatreId()));
            }
            if (StringUtils.hasText(filter.city())) {
                where.add(cb.equal(cb.lower(root.get("screen").get("theatre").get("city")),
                        filter.city().trim().toLowerCase()));
            }
            if (StringUtils.hasText(filter.language())) {
                where.add(cb.equal(cb.lower(root.get("language")), filter.language().trim().toLowerCase()));
            }
            if (StringUtils.hasText(filter.format())) {
                where.add(cb.equal(cb.lower(root.get("format")), filter.format().trim().toLowerCase()));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.<Instant>get("startTime"), filter.from()));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.<Instant>get("startTime"), filter.to()));
            }
            if (filter.status() != null) {
                where.add(cb.equal(root.get("status"), filter.status()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    @Transactional(readOnly = true)
    public ShowView get(long id) {
        Show show = shows.findById(id).orElseThrow(() -> ApiException.notFound("Show"));
        return toView(show, counts(List.of(show)).get(id), Instant.now(clock));
    }

    /** Polled by the seat page. Exposes only each seat's state, never who holds or owns it. */
    @Transactional(readOnly = true)
    public SeatMap seatMap(long showId) {
        Instant now = Instant.now(clock);
        Show show = shows.findById(showId).orElseThrow(() -> ApiException.notFound("Show"));
        List<ShowSeat> rows = new ArrayList<>(showSeats.findMap(showId));
        rows.sort(Comparator.comparing(ShowSeat::getSeat, CatalogService.seatOrder()));

        List<SeatState> states = rows.stream().map(row -> new SeatState(row.getSeat().getId(),
                row.getSeat().getRowLabel(), row.getSeat().getSeatNumber(), row.getSeat().getCategory().getName(),
                row.getPrice(), row.effectiveStatus(now))).toList();

        Map<String, List<ShowSeat>> byCategory = rows.stream().collect(Collectors.groupingBy(
                row -> row.getSeat().getCategory().getName(), LinkedHashMap::new, Collectors.toList()));
        List<CategoryPrice> categories = byCategory.entrySet().stream().map(entry -> new CategoryPrice(
                        entry.getKey(),
                        entry.getValue().stream().map(ShowSeat::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO),
                        entry.getValue().stream().filter(r -> r.effectiveStatus(now) == ShowSeatStatus.AVAILABLE).count()))
                .sorted(Comparator.comparing(CategoryPrice::price).reversed())
                .toList();

        return new SeatMap(toView(show, counts(List.of(show)).get(showId), now), categories, states, now);
    }

    @Transactional
    public ShowView create(AuthUser actor, ShowRequest request) {
        Instant now = Instant.now(clock);
        // Taken first so that the overlap check below cannot race another scheduler on the same screen.
        Screen screen = screens.lockById(request.screenId()).orElseThrow(() -> ApiException.notFound("Screen"));
        Movie movie = movies.findById(request.movieId()).orElseThrow(() -> ApiException.notFound("Movie"));
        if (!movie.isActive()) {
            throw ApiException.conflict("MOVIE_ARCHIVED", "This movie is archived");
        }
        if (!screen.isActive() || !screen.getTheatre().isActive()) {
            throw ApiException.conflict("SCREEN_INACTIVE", "This screen or theatre is not active");
        }
        // Schedules work in whole minutes, which also keeps the gap comparison free of timestamp rounding.
        Instant start = request.startTime().truncatedTo(ChronoUnit.MINUTES);
        if (!start.isAfter(now)) {
            throw ApiException.badRequest("SHOW_IN_PAST", "The start time must be in the future");
        }
        Instant end = start.plus(Duration.ofMinutes(movie.getDurationMinutes()));
        assertNoOverlap(screen.getId(), start, end, -1L);

        List<Seat> layout = seats.findActiveLayout(screen.getId());
        if (layout.isEmpty()) {
            throw ApiException.conflict("SCREEN_HAS_NO_SEATS", "Add seats to this screen before scheduling shows");
        }
        Map<Long, BigDecimal> overrides = request.categoryPrices() == null ? Map.of() : request.categoryPrices();
        overrides.values().forEach(price -> {
            if (price == null || price.signum() < 0) {
                throw ApiException.badRequest("INVALID_PRICE", "Prices cannot be negative");
            }
        });

        Show show = shows.save(new Show(movie, screen, start, end, request.language().trim(),
                request.format().trim(), now));
        showSeats.saveAll(layout.stream().map(seat -> new ShowSeat(show, seat,
                overrides.getOrDefault(seat.getCategory().getId(), seat.getCategory().getDefaultPrice()))).toList());
        audit.record(actor, "SHOW_CREATED", "SHOW", show.getId(),
                movie.getTitle() + " on " + screen.getName() + " at " + start);
        return toView(show, counts(List.of(show)).get(show.getId()), now);
    }

    /** Rescheduling is refused once tickets are sold: those customers bought a specific time. */
    @Transactional
    public ShowView update(AuthUser actor, long id, ShowUpdateRequest request) {
        Instant now = Instant.now(clock);
        Show show = shows.lockById(id).orElseThrow(() -> ApiException.notFound("Show"));
        if (show.getStatus() != ShowStatus.SCHEDULED) {
            throw ApiException.conflict("SHOW_CANCELLED", "A cancelled show cannot be changed");
        }
        if (!show.getStartTime().isAfter(now)) {
            throw ApiException.conflict("SHOW_ALREADY_STARTED", "This show has already started");
        }
        Instant start = request.startTime().truncatedTo(ChronoUnit.MINUTES);
        if (!start.isAfter(now)) {
            throw ApiException.badRequest("SHOW_IN_PAST", "The start time must be in the future");
        }
        if (!start.equals(show.getStartTime())) {
            screens.lockById(show.getScreen().getId());
            ShowSeatRepository.SeatCounts counts = counts(List.of(show)).get(id);
            if (counts != null && counts.getBooked() > 0) {
                throw ApiException.conflict("SHOW_HAS_BOOKINGS",
                        "This show has bookings. Cancel it, which refunds customers, and schedule a new one.");
            }
            Instant end = start.plus(Duration.ofMinutes(show.getMovie().getDurationMinutes()));
            assertNoOverlap(show.getScreen().getId(), start, end, id);
            show.setStartTime(start);
            show.setEndTime(end);
        }
        show.setLanguage(request.language().trim());
        show.setFormat(request.format().trim());
        audit.record(actor, "SHOW_UPDATED", "SHOW", id, "start=" + show.getStartTime() + " language="
                + show.getLanguage() + " format=" + show.getFormat());
        return toView(show, counts(List.of(show)).get(id), now);
    }

    private void assertNoOverlap(long screenId, Instant start, Instant end, long excludeShowId) {
        if (shows.existsOverlap(screenId, start.minus(cleaningBuffer), end.plus(cleaningBuffer), excludeShowId)) {
            throw ApiException.conflict("SHOW_OVERLAP",
                    "This overlaps another show on the same screen, including the " + cleaningBuffer.toMinutes()
                            + "-minute cleaning gap");
        }
    }

    private Map<Long, ShowSeatRepository.SeatCounts> counts(List<Show> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        return showSeats.countByShow(list.stream().map(Show::getId).toList(), Instant.now(clock)).stream()
                .collect(Collectors.toMap(ShowSeatRepository.SeatCounts::getShowId, Function.identity()));
    }

    private ShowView toView(Show show, ShowSeatRepository.SeatCounts counts, Instant now) {
        Movie movie = show.getMovie();
        Screen screen = show.getScreen();
        return new ShowView(show.getId(), movie.getId(), movie.getTitle(), movie.getCertificate(),
                movie.getDurationMinutes(), screen.getId(), screen.getName(), screen.getTheatre().getId(),
                screen.getTheatre().getName(), screen.getTheatre().getCity(), show.getStartTime(), show.getEndTime(),
                show.getLanguage(), show.getFormat(), show.getStatus(), show.isBookableAt(now),
                counts == null ? 0 : counts.getTotal(), counts == null ? 0 : counts.getAvailable(),
                counts == null ? null : counts.getMinPrice());
    }
}
