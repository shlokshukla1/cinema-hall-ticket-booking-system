package com.cinemahall.support;

import com.cinemahall.auth.Role;
import com.cinemahall.auth.User;
import com.cinemahall.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack test base: real HTTP layer through MockMvc, real security filters, real services and a real database.
 * Every test creates its own venue, show and users, so tests do not depend on one another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTestBase.ClockConfig.class)
public abstract class ApiTestBase {

    private static final String PASSWORD = "Password123";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    public record Response(int status, JsonNode body, MvcResult raw) {
        public String code() {
            return body.path("code").asText();
        }
    }

    public record ShowFixture(long showId, long screenId, long movieId, long categoryId, List<Long> seatIds) {
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    protected static String unique(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- HTTP helpers ----

    protected Response get(String token, String path) throws Exception {
        return send(MockMvcRequestBuilders.get(path), token, null);
    }

    protected Response post(String token, String path, Object body) throws Exception {
        return send(MockMvcRequestBuilders.post(path), token, body);
    }

    protected Response put(String token, String path, Object body) throws Exception {
        return send(MockMvcRequestBuilders.put(path), token, body);
    }

    protected Response patch(String token, String path, Object body) throws Exception {
        return send(MockMvcRequestBuilders.patch(path), token, body);
    }

    protected Response delete(String token, String path) throws Exception {
        return send(MockMvcRequestBuilders.delete(path), token, null);
    }

    protected Response send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString();
        JsonNode node = content.isBlank() || !content.trim().matches("^[\\[{].*(?s)")
                ? json.createObjectNode() : json.readTree(content);
        return new Response(result.getResponse().getStatus(), node, result);
    }

    protected Response expect(int status, Response response) {
        assertThat(response.status()).as("response body: %s", response.body()).isEqualTo(status);
        return response;
    }

    // ---- Users ----

    protected String tokenFor(Role role) throws Exception {
        String email = unique(role.name().toLowerCase()) + "@test.example";
        users.save(new User(email, passwordEncoder.encode(PASSWORD), "Test " + role, role, Instant.now()));
        return login(email, PASSWORD);
    }

    protected String login(String email, String password) throws Exception {
        return expect(200, post(null, "/api/auth/login", Map.of("email", email, "password", password)))
                .body().get("token").asText();
    }

    protected String admin() throws Exception {
        return tokenFor(Role.ADMIN);
    }

    protected String staff() throws Exception {
        return tokenFor(Role.STAFF);
    }

    protected String customer() throws Exception {
        return tokenFor(Role.CUSTOMER);
    }

    // ---- Domain fixtures ----

    protected long createMovie(String adminToken, int minutes) throws Exception {
        return expect(201, post(adminToken, "/api/admin/movies", Map.of(
                "title", unique("Movie"), "description", "A test film", "durationMinutes", minutes,
                "language", "English", "certificate", "U", "releaseDate", "2026-01-01")))
                .body().get("id").asLong();
    }

    protected long createCategory(String adminToken, String price) throws Exception {
        return expect(201, post(adminToken, "/api/admin/seat-categories", Map.of(
                "name", unique("Cat"), "defaultPrice", price, "sortOrder", 5)))
                .body().get("id").asLong();
    }

    protected long createScreen(String adminToken, long categoryId, int seats) throws Exception {
        long theatreId = expect(201, post(adminToken, "/api/admin/theatres", Map.of(
                "name", unique("Theatre"), "city", "Testville", "address", "1 Test Road")))
                .body().get("id").asLong();
        return expect(201, post(adminToken, "/api/admin/screens", Map.of(
                "theatreId", theatreId, "name", "Screen 1",
                "rows", List.of(Map.of("label", "A", "seats", seats, "categoryId", categoryId)))))
                .body().get("id").asLong();
    }

    /** A show with one row of seats at a price of 100.00 each, starting the given time from now. */
    protected ShowFixture createShow(Duration startsIn, int seats) throws Exception {
        String adminToken = admin();
        long categoryId = createCategory(adminToken, "100.00");
        long screenId = createScreen(adminToken, categoryId, seats);
        long movieId = createMovie(adminToken, 120);
        long showId = expect(201, post(adminToken, "/api/admin/shows", Map.of(
                "movieId", movieId, "screenId", screenId,
                "startTime", clock.instant().plus(startsIn).toString(),
                "language", "English", "format", "2D")))
                .body().get("id").asLong();
        return new ShowFixture(showId, screenId, movieId, categoryId, seatIds(showId));
    }

    protected ShowFixture createShow() throws Exception {
        return createShow(Duration.ofDays(3), 10);
    }

    protected List<Long> seatIds(long showId) throws Exception {
        List<Long> ids = new ArrayList<>();
        expect(200, get(null, "/api/shows/" + showId + "/seats")).body().get("seats")
                .forEach(seat -> ids.add(seat.get("seatId").asLong()));
        return ids;
    }

    protected String seatStatus(long showId, long seatId) throws Exception {
        for (JsonNode seat : get(null, "/api/shows/" + showId + "/seats").body().get("seats")) {
            if (seat.get("seatId").asLong() == seatId) {
                return seat.get("status").asText();
            }
        }
        throw new AssertionError("Seat " + seatId + " not found");
    }

    protected Response hold(String token, long showId, Long... seatIds) throws Exception {
        return post(token, "/api/holds", Map.of("showId", showId, "seatIds", List.of(seatIds)));
    }

    protected String holdReference(String token, long showId, Long... seatIds) throws Exception {
        return expect(201, hold(token, showId, seatIds)).body().get("reference").asText();
    }

    protected Response confirm(String token, String holdReference, String idempotencyKey) throws Exception {
        return confirm(token, holdReference, idempotencyKey, "SUCCESS");
    }

    protected Response confirm(String token, String holdReference, String idempotencyKey, String outcome) throws Exception {
        return send(MockMvcRequestBuilders.post("/api/bookings").header("Idempotency-Key", idempotencyKey), token,
                Map.of("holdReference", holdReference, "paymentMethod", "UPI", "simulatedOutcome", outcome));
    }

    /** Holds and pays in one step. Returns the booking body. */
    protected JsonNode book(String token, long showId, Long... seatIds) throws Exception {
        return expect(201, confirm(token, holdReference(token, showId, seatIds), UUID.randomUUID().toString())).body();
    }
}
