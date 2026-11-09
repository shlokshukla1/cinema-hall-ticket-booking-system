package com.cinemahall.show;

import com.cinemahall.common.PageResponse;
import com.cinemahall.show.ShowDtos.SeatMap;
import com.cinemahall.show.ShowDtos.ShowView;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/shows")
@Tag(name = "Showtimes (public)")
public class ShowController {

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    @GetMapping
    public PageResponse<ShowView> search(@RequestParam(required = false) Long movieId,
                                         @RequestParam(required = false) Long theatreId,
                                         @RequestParam(required = false) String city,
                                         @RequestParam(required = false) String language,
                                         @RequestParam(required = false) String format,
                                         @RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return shows.searchPublic(new ShowService.ShowFilter(movieId, theatreId, city, language, format, from, to, null),
                page, size);
    }

    @GetMapping("/{id}")
    public ShowView get(@PathVariable long id) {
        return shows.get(id);
    }

    @GetMapping("/{id}/seats")
    public SeatMap seats(@PathVariable long id) {
        return shows.seatMap(id);
    }
}
