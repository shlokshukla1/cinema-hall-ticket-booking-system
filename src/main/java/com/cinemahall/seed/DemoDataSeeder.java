package com.cinemahall.seed;

import com.cinemahall.auth.AuthUser;
import com.cinemahall.auth.Role;
import com.cinemahall.auth.User;
import com.cinemahall.auth.UserRepository;
import com.cinemahall.booking.BookingDtos.ConfirmRequest;
import com.cinemahall.booking.BookingDtos.HoldRequest;
import com.cinemahall.booking.BookingDtos.HoldView;
import com.cinemahall.booking.BookingService;
import com.cinemahall.booking.CancellationService;
import com.cinemahall.booking.Enums.PaymentMethod;
import com.cinemahall.booking.Enums.SimulatedOutcome;
import com.cinemahall.booking.HoldService;
import com.cinemahall.catalog.CatalogDtos.*;
import com.cinemahall.catalog.CatalogRepositories.GenreRepository;
import com.cinemahall.catalog.CatalogRepositories.SeatCategoryRepository;
import com.cinemahall.catalog.CatalogService;
import com.cinemahall.catalog.Genre;
import com.cinemahall.catalog.SeatCategory;
import com.cinemahall.show.ShowDtos.SeatState;
import com.cinemahall.show.ShowDtos.ShowRequest;
import com.cinemahall.show.ShowDtos.ShowView;
import com.cinemahall.show.ShowSeatStatus;
import com.cinemahall.show.ShowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fills an empty database with fictional demo data. It goes through the real services, so seeded bookings obey
 * every rule the application enforces. All credentials are dummies, documented in the README.
 * Skipped whenever any user exists, which makes it safe to run on every start.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CatalogService catalog;
    private final GenreRepository genres;
    private final SeatCategoryRepository categories;
    private final ShowService shows;
    private final HoldService holds;
    private final BookingService bookings;
    private final CancellationService cancellations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public DemoDataSeeder(UserRepository users, PasswordEncoder passwordEncoder, CatalogService catalog,
                          GenreRepository genres, SeatCategoryRepository categories, ShowService shows,
                          HoldService holds, BookingService bookings, CancellationService cancellations,
                          JdbcTemplate jdbc, Clock clock, @Value("${app.reporting.timezone}") String timezone) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.catalog = catalog;
        this.genres = genres;
        this.categories = categories;
        this.shows = shows;
        this.holds = holds;
        this.bookings = bookings;
        this.cancellations = cancellations;
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
    }

    private record MovieSeed(String title, String description, int minutes, String language, String certificate,
                             List<String> genres) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            log.info("Users already exist: demo data seeding skipped.");
            return;
        }
        log.info("Empty database: creating demo data. This takes a few seconds.");
        createUser("admin@cinema.test", "Admin@123", "Asha Admin", Role.ADMIN);
        AuthUser admin = users.findByEmail("admin@cinema.test").orElseThrow().toAuthUser();
        createUser("staff@cinema.test", "Staff@123", "Sam Staff", Role.STAFF);
        AuthUser customer = createUser("customer@cinema.test", "Customer@123", "Chris Customer", Role.CUSTOMER);
        AuthUser customerTwo = createUser("customer2@cinema.test", "Customer@123", "Dana Customer", Role.CUSTOMER);

        List<MovieView> movies = createMovies(admin);
        List<ScreenView> screens = createVenues(admin);
        List<ShowView> created = createShows(admin, movies, screens);
        createBookings(customer, customerTwo, created);
        log.info("Demo data ready: {} movies, {} screens, {} shows.", movies.size(), screens.size(), created.size());
    }

    private AuthUser createUser(String email, String password, String name, Role role) {
        return users.save(new User(email, passwordEncoder.encode(password), name, role, Instant.now(clock))).toAuthUser();
    }

    private List<MovieView> createMovies(AuthUser admin) {
        List<MovieSeed> seeds = List.of(
                new MovieSeed("The Lantern Keeper", "A lighthouse keeper's daughter follows a signal no ship has answered in forty years.", 128, "English", "UA", List.of("Mystery", "Drama")),
                new MovieSeed("Monsoon Protocol", "A flood-control engineer has one night to expose the sabotage of her city's dams.", 142, "Hindi", "UA", List.of("Thriller", "Action")),
                new MovieSeed("Paper Comets", "Two rival kite makers team up for the last festival their old town will ever hold.", 115, "Hindi", "U", List.of("Family", "Comedy")),
                new MovieSeed("Orbit of Small Things", "A repair robot on a forgotten space station adopts a very stubborn houseplant.", 98, "English", "U", List.of("Animation", "Sci-Fi", "Family")),
                new MovieSeed("Saffron Street", "A chef reopens a shuttered family restaurant and finds a decades-old love letter in the walls.", 124, "Tamil", "U", List.of("Romance", "Drama")),
                new MovieSeed("The Ninth Junction", "Every train that stops at a remote station arrives one day before it left.", 136, "English", "A", List.of("Sci-Fi", "Thriller")),
                new MovieSeed("Kingdom of Salt", "Rival caravans race across a white desert to reach a city that moves every season.", 151, "Telugu", "UA", List.of("Adventure", "Action")),
                new MovieSeed("Last Bench Heroes", "A failing school's back-row students enter a national robotics contest by accident.", 119, "Hindi", "U", List.of("Comedy", "Family")));
        Map<String, Long> genreIds = genres.findAll().stream().collect(Collectors.toMap(Genre::getName, Genre::getId));
        List<MovieView> created = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            MovieSeed seed = seeds.get(i);
            Set<Long> ids = seed.genres().stream().map(genreIds::get).collect(Collectors.toSet());
            created.add(catalog.createMovie(admin, new MovieRequest(seed.title(), seed.description(), seed.minutes(),
                    seed.language(), seed.certificate(), LocalDate.now(clock).minusDays(3L + i * 4L), ids)));
        }
        return created;
    }

    private List<ScreenView> createVenues(AuthUser admin) {
        List<SeatCategory> all = categories.findAllByOrderBySortOrderAscNameAsc();
        long recliner = all.get(0).getId();
        long premium = all.get(1).getId();
        long standard = all.get(2).getId();
        List<RowLayout> large = List.of(
                new RowLayout("A", 12, standard), new RowLayout("B", 12, standard), new RowLayout("C", 14, standard),
                new RowLayout("D", 14, premium), new RowLayout("E", 14, premium), new RowLayout("F", 10, recliner));
        List<RowLayout> small = List.of(
                new RowLayout("A", 10, standard), new RowLayout("B", 10, standard),
                new RowLayout("C", 10, premium), new RowLayout("D", 8, recliner));

        TheatreView grand = catalog.createTheatre(admin, new TheatreRequest("Grand Meridian Cinema", "Bengaluru", "12 Residency Road"));
        TheatreView regal = catalog.createTheatre(admin, new TheatreRequest("Starlight Talkies", "Mumbai", "48 Linking Road, Bandra"));
        return List.of(
                catalog.createScreen(admin, new ScreenRequest(grand.id(), "Screen 1", large)),
                catalog.createScreen(admin, new ScreenRequest(grand.id(), "Screen 2", small)),
                catalog.createScreen(admin, new ScreenRequest(regal.id(), "Audi A", large)));
    }

    private List<ShowView> createShows(AuthUser admin, List<MovieView> movies, List<ScreenView> screens) {
        int[][] slots = {{10, 0}, {14, 0}, {18, 30}};
        List<ShowView> created = new ArrayList<>();
        LocalDate firstDay = LocalDate.now(clock.withZone(zone)).plusDays(1);
        int movieIndex = 0;
        for (int day = 0; day < 5; day++) {
            for (ScreenView screen : screens) {
                for (int[] slot : slots) {
                    MovieView movie = movies.get(movieIndex++ % movies.size());
                    Instant start = firstDay.plusDays(day).atTime(slot[0], slot[1]).atZone(zone).toInstant();
                    String format = movieIndex % 3 == 0 ? "3D" : "2D";
                    created.add(shows.create(admin, new ShowRequest(movie.id(), screen.id(), start, movie.language(), format, null)));
                }
            }
        }
        return created;
    }

    /** Creates sample sales spread across recent days, so that reports have something to show on first run. */
    private void createBookings(AuthUser one, AuthUser two, List<ShowView> created) {
        int sale = 0;
        for (int showIndex = 0; showIndex < Math.min(created.size(), 18); showIndex++) {
            ShowView show = created.get(showIndex);
            List<SeatState> free = shows.seatMap(show.id()).seats().stream()
                    .filter(seat -> seat.status() == ShowSeatStatus.AVAILABLE).toList();
            int perCustomer = 2 + showIndex % 4;
            int cursor = 0;
            for (int round = 0; round < 3 + showIndex % 3; round++) {
                AuthUser buyer = round % 2 == 0 ? one : two;
                List<Long> seatIds = free.subList(cursor, cursor + perCustomer).stream().map(SeatState::seatId).toList();
                cursor += perCustomer;
                HoldView hold = holds.create(buyer, new HoldRequest(show.id(), seatIds));
                PaymentMethod method = PaymentMethod.values()[sale % PaymentMethod.values().length];
                String reference = bookings.confirm(buyer, UUID.randomUUID().toString(),
                        new ConfirmRequest(hold.reference(), method, SimulatedOutcome.SUCCESS)).booking().reference();
                backdate(reference, sale % 14);
                if (sale % 7 == 3) {
                    cancellations.cancel(buyer, reference, "CUSTOMER_REQUEST");
                }
                sale++;
            }
        }
    }

    /** Demo only: gives seeded sales a purchase date in the past, so that the sales chart shows a trend. */
    private void backdate(String reference, int daysAgo) {
        Timestamp at = Timestamp.from(Instant.now(clock).minus(Duration.ofDays(daysAgo)).truncatedTo(ChronoUnit.SECONDS));
        jdbc.update("UPDATE bookings SET created_at = ? WHERE reference = ?", at, reference);
        jdbc.update("UPDATE payments SET created_at = ? WHERE booking_id = (SELECT id FROM bookings WHERE reference = ?)", at, reference);
    }
}
