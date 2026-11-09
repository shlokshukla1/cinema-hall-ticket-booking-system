package com.cinemahall.show;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ShowDtos {

    private ShowDtos() {
    }

    public record ShowRequest(
            @NotNull(message = "Choose a movie") Long movieId,
            @NotNull(message = "Choose a screen") Long screenId,
            @NotNull(message = "Choose a start time") Instant startTime,
            @NotBlank(message = "Enter a language") @Size(max = 60) String language,
            @NotBlank(message = "Enter a format") @Size(max = 20) String format,
            /* Optional per-category price overrides, keyed by seat category id. */
            Map<Long, BigDecimal> categoryPrices) {
    }

    public record ShowUpdateRequest(
            @NotNull(message = "Choose a start time") Instant startTime,
            @NotBlank(message = "Enter a language") @Size(max = 60) String language,
            @NotBlank(message = "Enter a format") @Size(max = 20) String format) {
    }

    public record ShowView(Long id, Long movieId, String movieTitle, String certificate, int durationMinutes,
                           Long screenId, String screenName, Long theatreId, String theatreName, String city,
                           Instant startTime, Instant endTime, String language, String format, ShowStatus status,
                           boolean bookable, long seatsTotal, long seatsAvailable, BigDecimal minPrice) {
    }

    public record SeatState(Long seatId, String row, int number, String category, BigDecimal price,
                            ShowSeatStatus status) {
    }

    public record CategoryPrice(String category, BigDecimal price, long available) {
    }

    public record SeatMap(ShowView show, List<CategoryPrice> categories, List<SeatState> seats, Instant serverTime) {
    }
}
