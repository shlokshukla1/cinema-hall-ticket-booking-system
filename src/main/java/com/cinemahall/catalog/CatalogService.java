package com.cinemahall.catalog;

import com.cinemahall.audit.AuditService;
import com.cinemahall.auth.AuthUser;
import com.cinemahall.catalog.CatalogDtos.*;
import com.cinemahall.catalog.CatalogRepositories.*;
import com.cinemahall.common.ApiException;
import com.cinemahall.common.PageResponse;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Movies, genres, theatres, screens, seat layouts and seat categories. */
@Service
public class CatalogService {

    private final GenreRepository genres;
    private final MovieRepository movies;
    private final TheatreRepository theatres;
    private final ScreenRepository screens;
    private final SeatCategoryRepository categories;
    private final SeatRepository seats;
    private final AuditService audit;
    private final Clock clock;

    public CatalogService(GenreRepository genres, MovieRepository movies, TheatreRepository theatres,
                          ScreenRepository screens, SeatCategoryRepository categories, SeatRepository seats,
                          AuditService audit, Clock clock) {
        this.genres = genres;
        this.movies = movies;
        this.theatres = theatres;
        this.screens = screens;
        this.categories = categories;
        this.seats = seats;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- Genres ----

    @Transactional(readOnly = true)
    public List<GenreView> listGenres() {
        return genres.findAll(Sort.by("name")).stream().map(GenreView::from).toList();
    }

    @Transactional
    public GenreView createGenre(AuthUser actor, GenreRequest request) {
        Genre genre = genres.save(new Genre(request.name().trim()));
        audit.record(actor, "GENRE_CREATED", "GENRE", genre.getId(), genre.getName());
        return GenreView.from(genre);
    }

    @Transactional
    public GenreView updateGenre(AuthUser actor, long id, GenreRequest request) {
        Genre genre = genres.findById(id).orElseThrow(() -> ApiException.notFound("Genre"));
        genre.setName(request.name().trim());
        audit.record(actor, "GENRE_UPDATED", "GENRE", id, genre.getName());
        return GenreView.from(genre);
    }

    @Transactional
    public void deleteGenre(AuthUser actor, long id) {
        Genre genre = genres.findById(id).orElseThrow(() -> ApiException.notFound("Genre"));
        genres.delete(genre);
        genres.flush(); // surface a "still in use" foreign key failure here as a 409
        audit.record(actor, "GENRE_DELETED", "GENRE", id, genre.getName());
    }

    // ---- Movies ----

    @Transactional(readOnly = true)
    public PageResponse<MovieView> searchMovies(String q, Long genreId, String language, boolean includeArchived,
                                                int page, int size) {
        Specification<Movie> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (!includeArchived) {
                where.add(cb.isTrue(root.get("active")));
            }
            if (StringUtils.hasText(q)) {
                where.add(cb.like(cb.lower(root.get("title")), "%" + escapeLike(q) + "%", '\\'));
            }
            if (StringUtils.hasText(language)) {
                where.add(cb.equal(cb.lower(root.get("language")), language.trim().toLowerCase(Locale.ROOT)));
            }
            if (genreId != null) {
                where.add(cb.equal(root.join("genres", JoinType.INNER).get("id"), genreId));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return PageResponse.of(movies.findAll(spec, PageResponse.request(page, size, Sort.by("title"))), MovieView::from);
    }

    @Transactional(readOnly = true)
    public List<String> listLanguages() {
        return movies.findAll().stream().filter(Movie::isActive).map(Movie::getLanguage).distinct().sorted().toList();
    }

    @Transactional(readOnly = true)
    public MovieView getMovie(long id, boolean includeArchived) {
        Movie movie = movies.findById(id).filter(m -> includeArchived || m.isActive())
                .orElseThrow(() -> ApiException.notFound("Movie"));
        return MovieView.from(movie);
    }

    @Transactional
    public MovieView createMovie(AuthUser actor, MovieRequest request) {
        Movie movie = new Movie();
        apply(movie, request);
        movies.save(movie);
        audit.record(actor, "MOVIE_CREATED", "MOVIE", movie.getId(), movie.getTitle());
        return MovieView.from(movie);
    }

    @Transactional
    public MovieView updateMovie(AuthUser actor, long id, MovieRequest request) {
        Movie movie = movies.findById(id).orElseThrow(() -> ApiException.notFound("Movie"));
        apply(movie, request);
        audit.record(actor, "MOVIE_UPDATED", "MOVIE", id, movie.getTitle());
        return MovieView.from(movie);
    }

    /** Movies are archived, not deleted, because past shows and bookings still refer to them. */
    @Transactional
    public MovieView setMovieActive(AuthUser actor, long id, boolean active) {
        Movie movie = movies.findById(id).orElseThrow(() -> ApiException.notFound("Movie"));
        if (!active && movies.hasUpcomingShows(id, Instant.now(clock))) {
            throw ApiException.conflict("MOVIE_HAS_UPCOMING_SHOWS", "Cancel this movie's upcoming shows before archiving it");
        }
        movie.setActive(active);
        audit.record(actor, active ? "MOVIE_RESTORED" : "MOVIE_ARCHIVED", "MOVIE", id, movie.getTitle());
        return MovieView.from(movie);
    }

    private void apply(Movie movie, MovieRequest request) {
        movie.setTitle(request.title().trim());
        movie.setDescription(request.description());
        movie.setDurationMinutes(request.durationMinutes());
        movie.setLanguage(request.language().trim());
        movie.setCertificate(request.certificate().trim());
        movie.setReleaseDate(request.releaseDate());
        Set<Long> ids = request.genreIds() == null ? Set.of() : request.genreIds();
        List<Genre> found = genres.findAllById(ids);
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("UNKNOWN_GENRE", "One or more genres do not exist");
        }
        movie.setGenres(new HashSet<>(found));
    }

    // ---- Theatres ----

    @Transactional(readOnly = true)
    public List<TheatreView> listTheatres(String city, boolean includeInactive) {
        return theatres.findAll(Sort.by("city", "name")).stream()
                .filter(t -> includeInactive || t.isActive())
                .filter(t -> !StringUtils.hasText(city) || t.getCity().equalsIgnoreCase(city.trim()))
                .map(TheatreView::from)
                .toList();
    }

    @Transactional
    public TheatreView createTheatre(AuthUser actor, TheatreRequest request) {
        Theatre theatre = new Theatre();
        apply(theatre, request);
        theatres.save(theatre);
        audit.record(actor, "THEATRE_CREATED", "THEATRE", theatre.getId(), theatre.getName());
        return TheatreView.from(theatre);
    }

    @Transactional
    public TheatreView updateTheatre(AuthUser actor, long id, TheatreRequest request) {
        Theatre theatre = theatres.findById(id).orElseThrow(() -> ApiException.notFound("Theatre"));
        apply(theatre, request);
        audit.record(actor, "THEATRE_UPDATED", "THEATRE", id, theatre.getName());
        return TheatreView.from(theatre);
    }

    @Transactional
    public TheatreView setTheatreActive(AuthUser actor, long id, boolean active) {
        Theatre theatre = theatres.findById(id).orElseThrow(() -> ApiException.notFound("Theatre"));
        if (!active && theatres.hasUpcomingShows(id, Instant.now(clock))) {
            throw ApiException.conflict("THEATRE_HAS_UPCOMING_SHOWS", "Cancel this theatre's upcoming shows first");
        }
        theatre.setActive(active);
        audit.record(actor, active ? "THEATRE_ACTIVATED" : "THEATRE_DEACTIVATED", "THEATRE", id, theatre.getName());
        return TheatreView.from(theatre);
    }

    private void apply(Theatre theatre, TheatreRequest request) {
        theatre.setName(request.name().trim());
        theatre.setCity(request.city().trim());
        theatre.setAddress(request.address().trim());
    }

    // ---- Screens and seat layouts ----

    @Transactional(readOnly = true)
    public List<ScreenView> listScreens(long theatreId) {
        List<Screen> found = screens.findByTheatreIdOrderByName(theatreId);
        Map<Long, Long> counts = found.isEmpty() ? Map.of()
                : seats.countActive(found.stream().map(Screen::getId).toList()).stream()
                .collect(Collectors.toMap(ScreenSeatCount::getScreenId, ScreenSeatCount::getSeatCount));
        return found.stream().map(s -> ScreenView.from(s, counts.getOrDefault(s.getId(), 0L))).toList();
    }

    @Transactional
    public ScreenView createScreen(AuthUser actor, ScreenRequest request) {
        Theatre theatre = theatres.findById(request.theatreId()).orElseThrow(() -> ApiException.notFound("Theatre"));
        Screen screen = screens.save(new Screen(theatre, request.name().trim()));
        int created = createSeats(screen, request.rows());
        audit.record(actor, "SCREEN_CREATED", "SCREEN", screen.getId(), screen.getName() + ": " + created + " seats");
        return ScreenView.from(screen, created);
    }

    /** A layout can only be replaced before any show has used it; existing tickets refer to its seats. */
    @Transactional
    public ScreenView replaceLayout(AuthUser actor, long screenId, ScreenLayoutRequest request) {
        Screen screen = screens.lockById(screenId).orElseThrow(() -> ApiException.notFound("Screen"));
        if (screens.hasAnyShows(screenId)) {
            throw ApiException.conflict("LAYOUT_IN_USE",
                    "This screen already has shows. Edit individual seats instead: changes apply to future shows.");
        }
        seats.deleteByScreenId(screenId);
        seats.flush();
        int created = createSeats(screen, request.rows());
        audit.record(actor, "SCREEN_LAYOUT_REPLACED", "SCREEN", screenId, created + " seats");
        return ScreenView.from(screen, created);
    }

    @Transactional
    public ScreenView setScreenActive(AuthUser actor, long id, boolean active) {
        Screen screen = screens.findById(id).orElseThrow(() -> ApiException.notFound("Screen"));
        if (!active && screens.hasUpcomingShows(id, Instant.now(clock))) {
            throw ApiException.conflict("SCREEN_HAS_UPCOMING_SHOWS", "Cancel this screen's upcoming shows first");
        }
        screen.setActive(active);
        audit.record(actor, active ? "SCREEN_ACTIVATED" : "SCREEN_DEACTIVATED", "SCREEN", id, screen.getName());
        long count = seats.findActiveLayout(id).size();
        return ScreenView.from(screen, count);
    }

    @Transactional(readOnly = true)
    public List<SeatView> getLayout(long screenId) {
        if (!screens.existsById(screenId)) {
            throw ApiException.notFound("Screen");
        }
        return seats.findLayout(screenId).stream()
                .sorted(seatOrder())
                .map(SeatView::from)
                .toList();
    }

    /** Applies to shows scheduled later: existing shows keep the seats and prices they were created with. */
    @Transactional
    public SeatView updateSeat(AuthUser actor, long seatId, SeatUpdateRequest request) {
        Seat seat = seats.findById(seatId).orElseThrow(() -> ApiException.notFound("Seat"));
        SeatCategory category = categories.findById(request.categoryId())
                .orElseThrow(() -> ApiException.notFound("Seat category"));
        seat.setCategory(category);
        seat.setActive(request.active());
        audit.record(actor, "SEAT_UPDATED", "SEAT", seatId, seat.label() + " category=" + category.getName()
                + " active=" + request.active());
        return SeatView.from(seat);
    }

    public static Comparator<Seat> seatOrder() {
        return Comparator.comparingInt((Seat s) -> s.getRowLabel().length())
                .thenComparing(Seat::getRowLabel)
                .thenComparingInt(Seat::getSeatNumber);
    }

    private int createSeats(Screen screen, List<RowLayout> rows) {
        Set<String> labels = new HashSet<>();
        List<Long> categoryIds = rows.stream().map(RowLayout::categoryId).distinct().toList();
        Map<Long, SeatCategory> byId = categories.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(SeatCategory::getId, Function.identity()));
        List<Seat> created = new ArrayList<>();
        for (RowLayout row : rows) {
            String label = row.label().trim().toUpperCase(Locale.ROOT);
            if (!labels.add(label)) {
                throw ApiException.badRequest("DUPLICATE_ROW", "Row " + label + " appears more than once");
            }
            SeatCategory category = byId.get(row.categoryId());
            if (category == null) {
                throw ApiException.badRequest("UNKNOWN_CATEGORY", "Row " + label + " uses an unknown seat category");
            }
            for (int number = 1; number <= row.seats(); number++) {
                created.add(new Seat(screen, category, label, number));
            }
        }
        seats.saveAll(created);
        return created.size();
    }

    // ---- Seat categories ----

    @Transactional(readOnly = true)
    public List<SeatCategoryView> listCategories() {
        return categories.findAllByOrderBySortOrderAscNameAsc().stream().map(SeatCategoryView::from).toList();
    }

    @Transactional
    public SeatCategoryView createCategory(AuthUser actor, SeatCategoryRequest request) {
        SeatCategory category = categories.save(
                new SeatCategory(request.name().trim(), request.defaultPrice(), request.sortOrder()));
        audit.record(actor, "SEAT_CATEGORY_CREATED", "SEAT_CATEGORY", category.getId(),
                category.getName() + " @ " + category.getDefaultPrice());
        return SeatCategoryView.from(category);
    }

    @Transactional
    public SeatCategoryView updateCategory(AuthUser actor, long id, SeatCategoryRequest request) {
        SeatCategory category = categories.findById(id).orElseThrow(() -> ApiException.notFound("Seat category"));
        category.setName(request.name().trim());
        category.setDefaultPrice(request.defaultPrice());
        category.setSortOrder(request.sortOrder());
        audit.record(actor, "SEAT_CATEGORY_UPDATED", "SEAT_CATEGORY", id,
                category.getName() + " @ " + category.getDefaultPrice());
        return SeatCategoryView.from(category);
    }

    @Transactional
    public void deleteCategory(AuthUser actor, long id) {
        SeatCategory category = categories.findById(id).orElseThrow(() -> ApiException.notFound("Seat category"));
        categories.delete(category);
        categories.flush();
        audit.record(actor, "SEAT_CATEGORY_DELETED", "SEAT_CATEGORY", id, category.getName());
    }

    private static String escapeLike(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
