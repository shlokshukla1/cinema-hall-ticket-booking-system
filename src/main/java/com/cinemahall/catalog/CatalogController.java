package com.cinemahall.catalog;

import com.cinemahall.catalog.CatalogDtos.GenreView;
import com.cinemahall.catalog.CatalogDtos.MovieView;
import com.cinemahall.catalog.CatalogDtos.TheatreView;
import com.cinemahall.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Catalogue (public)")
public class CatalogController {

    private final CatalogService catalog;
    private final PosterService posters;

    public CatalogController(CatalogService catalog, PosterService posters) {
        this.catalog = catalog;
        this.posters = posters;
    }

    @GetMapping("/movies/{id}/poster")
    public ResponseEntity<Resource> poster(@PathVariable long id) {
        PosterService.Poster poster = posters.load(id);
        return ResponseEntity.ok()
                .contentType(poster.type())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)))
                .body(poster.file());
    }

    @GetMapping("/movies")
    public PageResponse<MovieView> movies(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) Long genreId,
                                          @RequestParam(required = false) String language,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "12") int size) {
        return catalog.searchMovies(q, genreId, language, false, page, size);
    }

    @GetMapping("/movies/languages")
    public List<String> languages() {
        return catalog.listLanguages();
    }

    @GetMapping("/movies/{id}")
    public MovieView movie(@PathVariable long id) {
        return catalog.getMovie(id, false);
    }

    @GetMapping("/genres")
    public List<GenreView> genres() {
        return catalog.listGenres();
    }

    @GetMapping("/theatres")
    public List<TheatreView> theatres(@RequestParam(required = false) String city) {
        return catalog.listTheatres(city, false);
    }
}
