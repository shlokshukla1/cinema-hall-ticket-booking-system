package com.cinemahall.seed;

import com.cinemahall.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seed_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class DemoDataSeederTest {

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seedCreatesAConsistentDemoAndIsSafeToRunAgain() {
        long usersBefore = users.count();
        long showsBefore = count("shows");
        long bookingsBefore = count("bookings");
        assertThat(usersBefore).isEqualTo(4);
        assertThat(showsBefore).isPositive();
        assertThat(bookingsBefore).isPositive();

        seeder.run(new DefaultApplicationArguments());

        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(count("shows")).isEqualTo(showsBefore);
        assertThat(count("bookings")).isEqualTo(bookingsBefore);

        // Every booked seat is owned by a live ticket, and vice versa.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_seats WHERE status = 'BOOKED'", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM booking_seats WHERE active_show_seat_id IS NOT NULL", Long.class));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
