package com.cinemahall;

import com.cinemahall.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityTest extends ApiTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"/api/bookings", "/api/auth/me", "/api/admin/bookings", "/api/admin/reports/sales",
            "/api/admin/audit-logs", "/api/admin/theatres", "/api/holds/active?showId=1"})
    void protectedEndpointsRequireAToken(String path) throws Exception {
        assertThat(expect(401, get(null, path)).code()).isEqualTo("UNAUTHENTICATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/bookings", "/api/admin/reports/sales", "/api/admin/reports/occupancy",
            "/api/admin/audit-logs", "/api/admin/shows", "/api/admin/movies"})
    void customersAndStaffAreForbiddenFromAdminEndpoints(String path) throws Exception {
        assertThat(expect(403, get(customer(), path)).code()).isEqualTo("FORBIDDEN");
        assertThat(expect(403, get(staff(), path)).code()).isEqualTo("FORBIDDEN");
        expect(200, get(admin(), path));
    }

    @Test
    void writeEndpointsAreEnforcedOnTheServerNotJustHiddenInTheUi() throws Exception {
        String customer = customer();
        Map<String, Object> movie = Map.of("title", "Sneaky", "durationMinutes", 90, "language", "English",
                "certificate", "U", "releaseDate", "2026-01-01");
        expect(403, post(customer, "/api/admin/movies", movie));
        expect(403, post(customer, "/api/admin/shows/1/cancel", null));
        expect(403, put(customer, "/api/admin/cancellation-rules", Map.of("rules", java.util.List.of())));
        expect(403, post(staff(), "/api/admin/movies", movie));
    }

    @Test
    void staffAndAdminsCanReachTheEntryGate() throws Exception {
        Map<String, String> unknown = Map.of("code", "NOT-A-TICKET");
        expect(404, post(staff(), "/api/staff/tickets/validate", unknown));
        expect(404, post(admin(), "/api/staff/tickets/validate", unknown));
    }

    @Test
    void aTamperedOrGarbageTokenIsRejected() throws Exception {
        String token = customer();
        String[] parts = token.split("\\.");
        // A valid header and payload with someone else's signature must fail.
        String forged = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        expect(401, get(forged, "/api/bookings"));
        expect(401, get("garbage", "/api/bookings"));
    }

    @Test
    void publicCatalogueNeedsNoLogin() throws Exception {
        expect(200, get(null, "/api/movies"));
        expect(200, get(null, "/api/genres"));
        expect(200, get(null, "/api/theatres"));
        expect(200, get(null, "/api/shows"));
    }

    @Test
    void registrationAlwaysCreatesACustomerWhateverTheRequestClaims() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("fullName", "Mallory");
        request.put("email", unique("mallory") + "@test.example");
        request.put("password", "Password123");
        request.put("role", "ADMIN");

        Response response = post(null, "/api/auth/register", request);

        // Unknown JSON properties are ignored, so "role" has no effect.
        JsonNode body = expect(201, response).body();
        assertThat(body.get("user").get("role").asText()).isEqualTo("CUSTOMER");
        expect(403, get(body.get("token").asText(), "/api/admin/bookings"));
    }

    @Test
    void passwordHashesAreNeverReturned() throws Exception {
        String email = unique("private") + "@test.example";
        Response registered = expect(201, post(null, "/api/auth/register",
                Map.of("fullName", "P", "email", email, "password", "Password123")));
        assertThat(registered.body().toString()).doesNotContain("password");
        assertThat(expect(200, get(registered.body().get("token").asText(), "/api/auth/me")).body().toString())
                .doesNotContain("password");
    }

    @Test
    void registrationValidatesInputAndRejectsDuplicateEmails() throws Exception {
        JsonNode errors = expect(400, post(null, "/api/auth/register",
                Map.of("fullName", "", "email", "not-an-email", "password", "short"))).body();
        assertThat(errors.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(errors.get("fieldErrors").findValuesAsText("field")).contains("fullName", "email", "password");

        String email = unique("dupe") + "@test.example";
        Map<String, String> request = Map.of("fullName", "D", "email", email, "password", "Password123");
        expect(201, post(null, "/api/auth/register", request));
        Map<String, String> sameEmailOtherCase = Map.of("fullName", "D", "email", email.toUpperCase(), "password", "Password123");
        assertThat(expect(409, post(null, "/api/auth/register", sameEmailOtherCase)).code()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void loginDoesNotRevealWhichEmailsExistAndLocksOutRepeatedFailures() throws Exception {
        String email = unique("victim") + "@test.example";
        expect(201, post(null, "/api/auth/register", Map.of("fullName", "V", "email", email, "password", "Password123")));

        Response wrongPassword = expect(401, post(null, "/api/auth/login", Map.of("email", email, "password", "wrong")));
        Response unknownUser = expect(401, post(null, "/api/auth/login",
                Map.of("email", unique("nobody") + "@test.example", "password", "wrong")));
        assertThat(wrongPassword.code()).isEqualTo(unknownUser.code());
        assertThat(wrongPassword.body().get("message")).isEqualTo(unknownUser.body().get("message"));

        for (int i = 0; i < 4; i++) {
            expect(401, post(null, "/api/auth/login", Map.of("email", email, "password", "wrong")));
        }
        // Locked now, even with the correct password.
        assertThat(expect(429, post(null, "/api/auth/login", Map.of("email", email, "password", "Password123"))).code())
                .isEqualTo("TOO_MANY_ATTEMPTS");

        clock.advance(java.time.Duration.ofMinutes(16));
        expect(200, post(null, "/api/auth/login", Map.of("email", email, "password", "Password123")));
    }

    @Test
    void malformedRequestsGetAConsistentErrorShape() throws Exception {
        assertThat(expect(400, get(null, "/api/movies/not-a-number")).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(expect(404, get(null, "/api/movies/999999999")).code()).isEqualTo("NOT_FOUND");
        assertThat(expect(404, get(null, "/api/shows/999999999/seats")).code()).isEqualTo("NOT_FOUND");
        JsonNode error = expect(400, post(customer(), "/api/holds", Map.of("seatIds", "nonsense"))).body();
        assertThat(error.has("timestamp")).isTrue();
        assertThat(error.has("path")).isTrue();
    }

    @Test
    void responsesCarryProtectiveHeaders() throws Exception {
        Response response = get(null, "/api/genres");
        assertThat(response.raw().getResponse().getHeader("Content-Security-Policy")).contains("script-src 'self'");
        assertThat(response.raw().getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
