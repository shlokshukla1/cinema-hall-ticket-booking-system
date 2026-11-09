package com.cinemahall;

import com.cinemahall.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOperationsTest extends ApiTestBase {

    private Map<String, Object> showRequest(long movieId, long screenId, Instant start) {
        return Map.of("movieId", movieId, "screenId", screenId, "startTime", start.toString(),
                "language", "English", "format", "2D");
    }

    @Test
    void schedulingRejectsOverlapsIncludingTheCleaningGap() throws Exception {
        String admin = admin();
        long category = createCategory(admin, "100.00");
        long screen = createScreen(admin, category, 4);
        long movie = createMovie(admin, 120);
        Instant start = clock.instant().plus(Duration.ofDays(2));
        expect(201, post(admin, "/api/admin/shows", showRequest(movie, screen, start)));

        // Overlaps the running time.
        assertThat(expect(409, post(admin, "/api/admin/shows", showRequest(movie, screen, start.plus(Duration.ofMinutes(60))))).code())
                .isEqualTo("SHOW_OVERLAP");
        // Ends cleanly but falls inside the 15-minute cleaning gap.
        assertThat(expect(409, post(admin, "/api/admin/shows", showRequest(movie, screen, start.plus(Duration.ofMinutes(130))))).code())
                .isEqualTo("SHOW_OVERLAP");
        // First slot after the gap.
        expect(201, post(admin, "/api/admin/shows", showRequest(movie, screen, start.plus(Duration.ofMinutes(135)))));
        // Same time on a different screen is fine.
        expect(201, post(admin, "/api/admin/shows", showRequest(movie, createScreen(admin, category, 4), start)));
    }

    @Test
    void schedulingRejectsPastStartsArchivedMoviesAndEmptyRequests() throws Exception {
        String admin = admin();
        long category = createCategory(admin, "100.00");
        long screen = createScreen(admin, category, 4);
        long movie = createMovie(admin, 120);

        assertThat(expect(400, post(admin, "/api/admin/shows", showRequest(movie, screen, clock.instant().minus(Duration.ofHours(1))))).code())
                .isEqualTo("SHOW_IN_PAST");
        assertThat(expect(400, post(admin, "/api/admin/shows", Map.of())).code()).isEqualTo("VALIDATION_FAILED");

        expect(200, patch(admin, "/api/admin/movies/" + movie + "/active", Map.of("active", false)));
        assertThat(expect(409, post(admin, "/api/admin/shows", showRequest(movie, screen, clock.instant().plus(Duration.ofDays(1))))).code())
                .isEqualTo("MOVIE_ARCHIVED");
    }

    @Test
    void pricesComeFromTheCategoryUnlessOverriddenAndStayFixedForTheShow() throws Exception {
        String admin = admin();
        long category = createCategory(admin, "150.00");
        long screen = createScreen(admin, category, 2);
        long movie = createMovie(admin, 100);
        Instant start = clock.instant().plus(Duration.ofDays(2));

        long standard = expect(201, post(admin, "/api/admin/shows", showRequest(movie, screen, start))).body().get("id").asLong();
        long special = expect(201, post(admin, "/api/admin/shows", Map.of("movieId", movie, "screenId", screen,
                "startTime", start.plus(Duration.ofDays(1)).toString(), "language", "English", "format", "3D",
                "categoryPrices", Map.of(String.valueOf(category), "225.50")))).body().get("id").asLong();

        assertThat(firstSeatPrice(standard)).isEqualTo("150.00");
        assertThat(firstSeatPrice(special)).isEqualTo("225.50");

        JsonNode categoryBody = expect(200, get(admin, "/api/admin/seat-categories")).body();
        String name = StreamSupport.stream(categoryBody.spliterator(), false)
                .filter(c -> c.get("id").asLong() == category).findFirst().orElseThrow().get("name").asText();
        expect(200, put(admin, "/api/admin/seat-categories/" + category,
                Map.of("name", name, "defaultPrice", "999.00", "sortOrder", 1)));
        assertThat(firstSeatPrice(standard)).as("existing shows keep their price").isEqualTo("150.00");
    }

    private String firstSeatPrice(long showId) throws Exception {
        return get(null, "/api/shows/" + showId + "/seats").body().get("seats").get(0).get("price").decimalValue()
                .setScale(2).toPlainString();
    }

    @Test
    void reschedulingIsAllowedBeforeSalesAndRefusedAfter() throws Exception {
        ShowFixture show = createShow();
        String admin = admin();
        Instant newStart = clock.instant().plus(Duration.ofDays(4));
        Map<String, Object> update = Map.of("startTime", newStart.toString(), "language", "Hindi", "format", "3D");

        JsonNode moved = expect(200, put(admin, "/api/admin/shows/" + show.showId(), update)).body();
        assertThat(moved.get("language").asText()).isEqualTo("Hindi");
        assertThat(Instant.parse(moved.get("startTime").asText())).isEqualTo(newStart.truncatedTo(ChronoUnit.MINUTES));

        book(customer(), show.showId(), show.seatIds().get(0));
        Map<String, Object> again = Map.of("startTime", newStart.plus(Duration.ofDays(1)).toString(), "language", "Hindi", "format", "3D");
        assertThat(expect(409, put(admin, "/api/admin/shows/" + show.showId(), again)).code()).isEqualTo("SHOW_HAS_BOOKINGS");
    }

    @Test
    void catalogueIntegrityIsEnforced() throws Exception {
        ShowFixture show = createShow();
        String admin = admin();

        assertThat(expect(409, patch(admin, "/api/admin/movies/" + show.movieId() + "/active", Map.of("active", false))).code())
                .isEqualTo("MOVIE_HAS_UPCOMING_SHOWS");
        assertThat(expect(409, delete(admin, "/api/admin/seat-categories/" + show.categoryId())).code())
                .isEqualTo("DATA_CONFLICT");
        assertThat(expect(409, put(admin, "/api/admin/screens/" + show.screenId() + "/layout",
                Map.of("rows", List.of(Map.of("label", "Z", "seats", 2, "categoryId", show.categoryId()))))).code())
                .isEqualTo("LAYOUT_IN_USE");
        assertThat(expect(400, post(admin, "/api/admin/seat-categories",
                Map.of("name", "Negative", "defaultPrice", "-1", "sortOrder", 1))).code()).isEqualTo("VALIDATION_FAILED");

        String genre = unique("Genre");
        long genreId = expect(201, post(admin, "/api/admin/genres", Map.of("name", genre))).body().get("id").asLong();
        assertThat(expect(409, post(admin, "/api/admin/genres", Map.of("name", genre))).code()).isEqualTo("DATA_CONFLICT");
        expect(204, delete(admin, "/api/admin/genres/" + genreId));
    }

    @Test
    void screenLayoutsValidateRowsAndCanBeEditedBeforeFirstUse() throws Exception {
        String admin = admin();
        long category = createCategory(admin, "100.00");
        long screen = createScreen(admin, category, 3);
        assertThat(expect(200, get(admin, "/api/admin/screens/" + screen + "/seats")).body()).hasSize(3);

        assertThat(expect(400, put(admin, "/api/admin/screens/" + screen + "/layout", Map.of("rows", List.of(
                Map.of("label", "A", "seats", 2, "categoryId", category),
                Map.of("label", "a", "seats", 2, "categoryId", category))))).code()).isEqualTo("DUPLICATE_ROW");

        expect(200, put(admin, "/api/admin/screens/" + screen + "/layout", Map.of("rows", List.of(
                Map.of("label", "A", "seats", 2, "categoryId", category),
                Map.of("label", "B", "seats", 3, "categoryId", category)))));
        JsonNode seats = get(admin, "/api/admin/screens/" + screen + "/seats").body();
        assertThat(seats).hasSize(5);

        // A seat taken out of service is not sold at shows scheduled afterwards.
        expect(200, put(admin, "/api/admin/seats/" + seats.get(0).get("id").asLong(),
                Map.of("categoryId", category, "active", false)));
        long showId = expect(201, post(admin, "/api/admin/shows", showRequest(createMovie(admin, 90), screen,
                clock.instant().plus(Duration.ofDays(1))))).body().get("id").asLong();
        assertThat(seatIds(showId)).hasSize(4);
    }

    @Test
    void cancellationPolicyIsConfigurable() throws Exception {
        String admin = admin();
        List<Map<String, Integer>> original = List.of(
                Map.of("minHoursBefore", 24, "refundPercent", 100), Map.of("minHoursBefore", 4, "refundPercent", 50));
        try {
            expect(200, put(admin, "/api/admin/cancellation-rules", Map.of("rules",
                    List.of(Map.of("minHoursBefore", 1, "refundPercent", 25)))));
            ShowFixture show = createShow(Duration.ofHours(2), 2);
            String customer = customer();
            String reference = book(customer, show.showId(), show.seatIds().get(0)).get("reference").asText();

            JsonNode cancelled = expect(200, post(customer, "/api/bookings/" + reference + "/cancel", null)).body();
            assertThat(cancelled.get("payment").get("refundAmount").decimalValue()).isEqualByComparingTo("25.00");

            assertThat(expect(400, put(admin, "/api/admin/cancellation-rules", Map.of("rules", List.of(
                    Map.of("minHoursBefore", 1, "refundPercent", 10), Map.of("minHoursBefore", 1, "refundPercent", 20))))).code())
                    .isEqualTo("DUPLICATE_RULE");
            assertThat(expect(400, put(admin, "/api/admin/cancellation-rules", Map.of("rules", List.of(
                    Map.of("minHoursBefore", 1, "refundPercent", 101))))).code()).isEqualTo("VALIDATION_FAILED");
        } finally {
            expect(200, put(admin, "/api/admin/cancellation-rules", Map.of("rules", original)));
        }
    }

    @Test
    void materialActionsLeaveAnAuditTrailIncludingFailedAttempts() throws Exception {
        ShowFixture show = createShow();
        String admin = admin();
        String customer = customer();
        String hold = holdReference(customer, show.showId(), show.seatIds().get(0));
        expect(402, confirm(customer, hold, "declined", "DECLINE"));
        String reference = expect(201, confirm(customer, hold, "paid")).body().get("reference").asText();
        expect(200, post(staff(), "/api/staff/tickets/validate", Map.of("code", reference)));
        expect(409, post(staff(), "/api/staff/tickets/validate", Map.of("code", reference)));

        assertAudited(admin, "SHOW_CREATED", String.valueOf(show.showId()));
        assertAudited(admin, "SEATS_HELD", hold);
        assertAudited(admin, "PAYMENT_DECLINED", hold);
        assertAudited(admin, "BOOKING_CONFIRMED", reference);
        assertAudited(admin, "TICKET_VALIDATED", reference);
        assertAudited(admin, "TICKET_REJECTED", reference);
    }

    @Test
    void aFailedActionLeavesNoSuccessRecordBehind() throws Exception {
        ShowFixture show = createShow();
        String customer = customer();
        String taken = holdReference(customer, show.showId(), show.seatIds().get(0));
        String other = customer();

        expect(409, hold(other, show.showId(), show.seatIds().get(0)));

        JsonNode rows = expect(200, get(admin(), "/api/admin/audit-logs?action=SEATS_HELD&size=100")).body().get("content");
        long forThisShow = StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> row.get("details").asText().startsWith("show=" + show.showId() + " ")).count();
        assertThat(forThisShow).isEqualTo(1);
        assertThat(taken).isNotBlank();
    }

    private void assertAudited(String admin, String action, String entityId) throws Exception {
        JsonNode rows = expect(200, get(admin, "/api/admin/audit-logs?size=100&action=" + action)).body().get("content");
        assertThat(StreamSupport.stream(rows.spliterator(), false).map(row -> row.get("entityId").asText()))
                .as("audit rows for %s", action).contains(entityId);
    }

    @Test
    void movieSearchFiltersAndPaginates() throws Exception {
        String admin = admin();
        String marker = unique("Zebra");
        long genre = expect(201, post(admin, "/api/admin/genres", Map.of("name", unique("G")))).body().get("id").asLong();
        for (int i = 0; i < 3; i++) {
            expect(201, post(admin, "/api/admin/movies", Map.of("title", marker + " " + i, "durationMinutes", 90,
                    "language", "Kannada", "certificate", "U", "releaseDate", "2026-01-01", "genreIds", List.of(genre))));
        }

        JsonNode page = expect(200, get(null, "/api/movies?size=2&q=" + marker.toLowerCase())).body();
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("content")).hasSize(2);
        assertThat(get(null, "/api/movies?genreId=" + genre).body().get("totalElements").asInt()).isEqualTo(3);
        assertThat(get(null, "/api/movies?q=" + marker + "&language=tamil").body().get("totalElements").asInt()).isZero();
        // Wildcards in a search are treated as literal text.
        assertThat(get(null, "/api/movies?q=%25%25%25nothing").body().get("totalElements").asInt()).isZero();
    }

    @Test
    void showSearchHidesPastAndCancelledShowsFromCustomers() throws Exception {
        ShowFixture cancelled = createShow();
        ShowFixture running = createShow();
        String admin = admin();
        expect(200, post(admin, "/api/admin/shows/" + cancelled.showId() + "/cancel", null));

        assertThat(get(null, "/api/shows?movieId=" + cancelled.movieId()).body().get("totalElements").asInt()).isZero();
        JsonNode listed = get(null, "/api/shows?movieId=" + running.movieId()).body();
        assertThat(listed.get("totalElements").asInt()).isEqualTo(1);
        assertThat(listed.get("content").get(0).get("seatsAvailable").asInt()).isEqualTo(10);
        assertThat(listed.get("content").get(0).get("bookable").asBoolean()).isTrue();

        clock.advance(Duration.ofDays(5));
        assertThat(get(null, "/api/shows?movieId=" + running.movieId()).body().get("totalElements").asInt()).isZero();
        assertThat(get(admin, "/api/admin/shows?movieId=" + running.movieId()).body().get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void openApiDocumentIsPublished() throws Exception {
        JsonNode doc = expect(200, get(null, "/v3/api-docs")).body();
        assertThat(doc.get("paths").has("/api/bookings")).isTrue();
        assertThat(doc.get("paths").has("/api/holds")).isTrue();
        assertThat(doc.get("components").get("securitySchemes").has("bearer")).isTrue();
    }
}
