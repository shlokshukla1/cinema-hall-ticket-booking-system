package com.cinemahall.catalog;

import com.cinemahall.auth.CurrentUser;
import com.cinemahall.catalog.CatalogDtos.*;
import com.cinemahall.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin: cinema configuration")
public class AdminCatalogController {

    private final CatalogService catalog;
    private final PosterService posters;

    public AdminCatalogController(CatalogService catalog, PosterService posters) {
        this.catalog = catalog;
        this.posters = posters;
    }

    @PostMapping(value = "/movies/{id}/poster", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MovieView uploadPoster(@PathVariable long id, @RequestPart("file") MultipartFile file) {
        return posters.upload(CurrentUser.require(), id, file);
    }

    @DeleteMapping("/movies/{id}/poster")
    public MovieView removePoster(@PathVariable long id) {
        return posters.remove(CurrentUser.require(), id);
    }

    // ---- Genres ----

    @PostMapping("/genres")
    @ResponseStatus(HttpStatus.CREATED)
    public GenreView createGenre(@Valid @RequestBody GenreRequest request) {
        return catalog.createGenre(CurrentUser.require(), request);
    }

    @PutMapping("/genres/{id}")
    public GenreView updateGenre(@PathVariable long id, @Valid @RequestBody GenreRequest request) {
        return catalog.updateGenre(CurrentUser.require(), id, request);
    }

    @DeleteMapping("/genres/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGenre(@PathVariable long id) {
        catalog.deleteGenre(CurrentUser.require(), id);
    }

    // ---- Movies ----

    @GetMapping("/movies")
    public PageResponse<MovieView> movies(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) Long genreId,
                                          @RequestParam(required = false) String language,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return catalog.searchMovies(q, genreId, language, true, page, size);
    }

    @PostMapping("/movies")
    @ResponseStatus(HttpStatus.CREATED)
    public MovieView createMovie(@Valid @RequestBody MovieRequest request) {
        return catalog.createMovie(CurrentUser.require(), request);
    }

    @PutMapping("/movies/{id}")
    public MovieView updateMovie(@PathVariable long id, @Valid @RequestBody MovieRequest request) {
        return catalog.updateMovie(CurrentUser.require(), id, request);
    }

    @PatchMapping("/movies/{id}/active")
    public MovieView setMovieActive(@PathVariable long id, @RequestBody ActiveRequest request) {
        return catalog.setMovieActive(CurrentUser.require(), id, request.active());
    }

    // ---- Theatres ----

    @GetMapping("/theatres")
    public List<TheatreView> theatres() {
        return catalog.listTheatres(null, true);
    }

    @PostMapping("/theatres")
    @ResponseStatus(HttpStatus.CREATED)
    public TheatreView createTheatre(@Valid @RequestBody TheatreRequest request) {
        return catalog.createTheatre(CurrentUser.require(), request);
    }

    @PutMapping("/theatres/{id}")
    public TheatreView updateTheatre(@PathVariable long id, @Valid @RequestBody TheatreRequest request) {
        return catalog.updateTheatre(CurrentUser.require(), id, request);
    }

    @PatchMapping("/theatres/{id}/active")
    public TheatreView setTheatreActive(@PathVariable long id, @RequestBody ActiveRequest request) {
        return catalog.setTheatreActive(CurrentUser.require(), id, request.active());
    }

    // ---- Screens and seats ----

    @GetMapping("/theatres/{theatreId}/screens")
    public List<ScreenView> screens(@PathVariable long theatreId) {
        return catalog.listScreens(theatreId);
    }

    @PostMapping("/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenView createScreen(@Valid @RequestBody ScreenRequest request) {
        return catalog.createScreen(CurrentUser.require(), request);
    }

    @PutMapping("/screens/{id}/layout")
    public ScreenView replaceLayout(@PathVariable long id, @Valid @RequestBody ScreenLayoutRequest request) {
        return catalog.replaceLayout(CurrentUser.require(), id, request);
    }

    @PatchMapping("/screens/{id}/active")
    public ScreenView setScreenActive(@PathVariable long id, @RequestBody ActiveRequest request) {
        return catalog.setScreenActive(CurrentUser.require(), id, request.active());
    }

    @GetMapping("/screens/{id}/seats")
    public List<SeatView> layout(@PathVariable long id) {
        return catalog.getLayout(id);
    }

    @PutMapping("/seats/{id}")
    public SeatView updateSeat(@PathVariable long id, @Valid @RequestBody SeatUpdateRequest request) {
        return catalog.updateSeat(CurrentUser.require(), id, request);
    }

    // ---- Seat categories ----

    @GetMapping("/seat-categories")
    public List<SeatCategoryView> categories() {
        return catalog.listCategories();
    }

    @PostMapping("/seat-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public SeatCategoryView createCategory(@Valid @RequestBody SeatCategoryRequest request) {
        return catalog.createCategory(CurrentUser.require(), request);
    }

    @PutMapping("/seat-categories/{id}")
    public SeatCategoryView updateCategory(@PathVariable long id, @Valid @RequestBody SeatCategoryRequest request) {
        return catalog.updateCategory(CurrentUser.require(), id, request);
    }

    @DeleteMapping("/seat-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable long id) {
        catalog.deleteCategory(CurrentUser.require(), id);
    }
}
