package com.cinemahall.booking;

import com.cinemahall.auth.CurrentUser;
import com.cinemahall.booking.BookingDtos.*;
import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Endpoints for staff at the door and admins managing bookings and policy. */
@RestController
@RequestMapping("/api")
@Tag(name = "Operations (staff and admin)")
public class OperationsController {

    private final TicketService ticketService;
    private final BookingService bookingService;
    private final CancellationService cancellationService;

    public OperationsController(TicketService ticketService, BookingService bookingService,
                                CancellationService cancellationService) {
        this.ticketService = ticketService;
        this.bookingService = bookingService;
        this.cancellationService = cancellationService;
    }

    @PostMapping("/staff/tickets/validate")
    public TicketValidationResult validate(@Valid @RequestBody TicketValidationRequest request) {
        return ticketService.validate(CurrentUser.require(), request.code());
    }

    @GetMapping("/admin/bookings")
    public PageResponse<BookingView> bookings(@RequestParam(required = false) BookingStatus status,
                                              @RequestParam(required = false) Long showId,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return bookingService.searchAll(status, showId, q, page, size);
    }

    @PostMapping("/admin/shows/{id}/cancel")
    public Map<String, Integer> cancelShow(@PathVariable long id) {
        return Map.of("refundedBookings", cancellationService.cancelShow(CurrentUser.require(), id));
    }

    @GetMapping("/cancellation-rules")
    public List<CancellationRuleView> rules() {
        return cancellationService.listRules();
    }

    @PutMapping("/admin/cancellation-rules")
    public List<CancellationRuleView> replaceRules(@Valid @RequestBody CancellationRulesRequest request) {
        return cancellationService.replaceRules(CurrentUser.require(), request);
    }
}
