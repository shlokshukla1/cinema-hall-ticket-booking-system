package com.cinemahall.booking;

import com.cinemahall.auth.CurrentUser;
import com.cinemahall.booking.BookingDtos.*;
import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Holds and bookings (customer)")
public class BookingController {

    private final HoldService holdService;
    private final BookingService bookingService;
    private final CancellationService cancellationService;
    private final TicketService ticketService;

    public BookingController(HoldService holdService, BookingService bookingService,
                             CancellationService cancellationService, TicketService ticketService) {
        this.holdService = holdService;
        this.bookingService = bookingService;
        this.cancellationService = cancellationService;
        this.ticketService = ticketService;
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Hold seats. All are claimed or none; 409 SEATS_UNAVAILABLE if any is taken.")
    public HoldView createHold(@Valid @RequestBody HoldRequest request) {
        return holdService.create(CurrentUser.require(), request);
    }

    @GetMapping("/holds/active")
    @Operation(summary = "The caller's live hold for a show, or 204 if there is none")
    public ResponseEntity<HoldView> activeHold(@RequestParam long showId) {
        return holdService.findLive(CurrentUser.require(), showId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/holds/{reference}")
    public HoldView getHold(@PathVariable String reference) {
        return holdService.get(CurrentUser.require(), reference);
    }

    @DeleteMapping("/holds/{reference}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseHold(@PathVariable String reference) {
        holdService.release(CurrentUser.require(), reference);
    }

    @PostMapping("/bookings")
    @Operation(summary = "Pay (simulated) and confirm a hold",
            description = "Send a unique Idempotency-Key per attempt. Repeating a key returns the original booking "
                    + "with status 200 instead of creating a second one.")
    public ResponseEntity<BookingView> confirm(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody ConfirmRequest request) {
        BookingService.ConfirmResult result = bookingService.confirm(CurrentUser.require(), idempotencyKey, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.booking());
    }

    @GetMapping("/bookings")
    public PageResponse<BookingView> myBookings(@RequestParam(required = false) BookingStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return bookingService.listForCustomer(CurrentUser.require(), status, page, size);
    }

    @GetMapping("/bookings/{reference}")
    public BookingView getBooking(@PathVariable String reference) {
        return bookingService.getForCaller(CurrentUser.require(), reference);
    }

    @GetMapping(value = "/bookings/{reference}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qr(@PathVariable String reference) {
        return ticketService.qrPng(CurrentUser.require(), reference);
    }

    @GetMapping("/bookings/{reference}/cancellation-quote")
    public CancellationQuoteView quote(@PathVariable String reference) {
        return cancellationService.quote(CurrentUser.require(), reference);
    }

    @PostMapping("/bookings/{reference}/cancel")
    public BookingView cancel(@PathVariable String reference, @Valid @RequestBody(required = false) CancelRequest request) {
        return cancellationService.cancel(CurrentUser.require(), reference, request == null ? null : request.reason());
    }
}
