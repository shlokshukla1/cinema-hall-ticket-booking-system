package com.cinemahall.show;

import com.cinemahall.auth.CurrentUser;
import com.cinemahall.common.PageResponse;
import com.cinemahall.show.ShowDtos.ShowRequest;
import com.cinemahall.show.ShowDtos.ShowUpdateRequest;
import com.cinemahall.show.ShowDtos.ShowView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/shows")
@Tag(name = "Admin: schedules")
public class AdminShowController {

    private final ShowService shows;

    public AdminShowController(ShowService shows) {
        this.shows = shows;
    }

    @GetMapping
    public PageResponse<ShowView> search(@RequestParam(required = false) Long movieId,
                                         @RequestParam(required = false) Long theatreId,
                                         @RequestParam(required = false) ShowStatus status,
                                         @RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return shows.searchAdmin(new ShowService.ShowFilter(movieId, theatreId, null, null, null, from, to, status),
                page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowView create(@Valid @RequestBody ShowRequest request) {
        return shows.create(CurrentUser.require(), request);
    }

    @PutMapping("/{id}")
    public ShowView update(@PathVariable long id, @Valid @RequestBody ShowUpdateRequest request) {
        return shows.update(CurrentUser.require(), id, request);
    }
}
