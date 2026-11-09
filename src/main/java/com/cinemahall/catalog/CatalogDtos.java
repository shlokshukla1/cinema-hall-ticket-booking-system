package com.cinemahall.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record GenreRequest(@NotBlank(message = "Enter a name") @Size(max = 60) String name) {
    }

    public record GenreView(Long id, String name) {
        static GenreView from(Genre genre) {
            return new GenreView(genre.getId(), genre.getName());
        }
    }

    public record MovieRequest(
            @NotBlank(message = "Enter a title") @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull(message = "Enter the running time") @Min(1) @Max(600) Integer durationMinutes,
            @NotBlank(message = "Enter a language") @Size(max = 60) String language,
            @NotBlank(message = "Enter a certificate") @Size(max = 10) String certificate,
            @NotNull(message = "Enter a release date") LocalDate releaseDate,
            Set<Long> genreIds) {
    }

    public record MovieView(Long id, String title, String description, int durationMinutes, String language,
                            String certificate, LocalDate releaseDate, boolean active, List<GenreView> genres,
                            String posterUrl) {
        static MovieView from(Movie movie) {
            List<GenreView> genres = movie.getGenres().stream()
                    .map(GenreView::from)
                    .sorted(Comparator.comparing(GenreView::name))
                    .toList();
            // The file name in the query string makes browsers fetch a replaced poster instead of a cached one.
            String posterUrl = movie.getPosterFile() == null ? null
                    : "/api/movies/" + movie.getId() + "/poster?v=" + movie.getPosterFile();
            return new MovieView(movie.getId(), movie.getTitle(), movie.getDescription(), movie.getDurationMinutes(),
                    movie.getLanguage(), movie.getCertificate(), movie.getReleaseDate(), movie.isActive(), genres,
                    posterUrl);
        }
    }

    public record TheatreRequest(
            @NotBlank(message = "Enter a name") @Size(max = 120) String name,
            @NotBlank(message = "Enter a city") @Size(max = 80) String city,
            @NotBlank(message = "Enter an address") @Size(max = 255) String address) {
    }

    public record TheatreView(Long id, String name, String city, String address, boolean active) {
        static TheatreView from(Theatre theatre) {
            return new TheatreView(theatre.getId(), theatre.getName(), theatre.getCity(), theatre.getAddress(),
                    theatre.isActive());
        }
    }

    public record SeatCategoryRequest(
            @NotBlank(message = "Enter a name") @Size(max = 40) String name,
            @NotNull(message = "Enter a price") @DecimalMin(value = "0.00", message = "Price cannot be negative")
            @Digits(integer = 8, fraction = 2) BigDecimal defaultPrice,
            @Min(0) int sortOrder) {
    }

    public record SeatCategoryView(Long id, String name, BigDecimal defaultPrice, int sortOrder) {
        static SeatCategoryView from(SeatCategory category) {
            return new SeatCategoryView(category.getId(), category.getName(), category.getDefaultPrice(),
                    category.getSortOrder());
        }
    }

    public record RowLayout(
            @NotBlank @Size(max = 4) @Pattern(regexp = "^[A-Za-z0-9]+$", message = "Use letters and numbers only")
            String label,
            @Min(value = 1, message = "A row needs at least one seat") @Max(50) int seats,
            @NotNull(message = "Choose a category") Long categoryId) {
    }

    public record ScreenRequest(
            @NotNull(message = "Choose a theatre") Long theatreId,
            @NotBlank(message = "Enter a name") @Size(max = 60) String name,
            @NotEmpty(message = "Add at least one row") @Size(max = 40) @Valid List<RowLayout> rows) {
    }

    public record ScreenLayoutRequest(
            @NotEmpty(message = "Add at least one row") @Size(max = 40) @Valid List<RowLayout> rows) {
    }

    public record ScreenView(Long id, Long theatreId, String theatreName, String name, boolean active, long seatCount) {
        static ScreenView from(Screen screen, long seatCount) {
            return new ScreenView(screen.getId(), screen.getTheatre().getId(), screen.getTheatre().getName(),
                    screen.getName(), screen.isActive(), seatCount);
        }
    }

    public record SeatView(Long id, String rowLabel, int seatNumber, Long categoryId, String categoryName,
                           boolean active) {
        static SeatView from(Seat seat) {
            return new SeatView(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getCategory().getId(),
                    seat.getCategory().getName(), seat.isActive());
        }
    }

    public record SeatUpdateRequest(@NotNull Long categoryId, boolean active) {
    }

    public record ActiveRequest(boolean active) {
    }
}
