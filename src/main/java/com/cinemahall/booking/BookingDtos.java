package com.cinemahall.booking;

import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.booking.Enums.HoldStatus;
import com.cinemahall.booking.Enums.PaymentMethod;
import com.cinemahall.booking.Enums.PaymentStatus;
import com.cinemahall.booking.Enums.RefundStatus;
import com.cinemahall.booking.Enums.SimulatedOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record HoldRequest(
            @NotNull(message = "Choose a show") Long showId,
            @NotEmpty(message = "Select at least one seat") @Size(max = 50) List<@NotNull Long> seatIds) {
    }

    public record SeatLine(String label, String category, BigDecimal price) {
    }

    public record HoldView(String reference, Long showId, HoldStatus status, List<SeatLine> seats,
                           List<Long> seatIds, BigDecimal totalAmount, Instant expiresAt, Instant serverTime) {
    }

    public record ConfirmRequest(
            @NotBlank(message = "A hold is required") String holdReference,
            @NotNull(message = "Choose a payment method") PaymentMethod paymentMethod,
            SimulatedOutcome simulatedOutcome) {
    }

    public record PaymentView(String providerRef, PaymentMethod method, BigDecimal amount, PaymentStatus status,
                              BigDecimal refundAmount, int refundPercent, RefundStatus refundStatus,
                              Instant refundedAt) {
    }

    public record BookingView(String reference, BookingStatus status, Long showId, Long movieId, String movieTitle,
                              String theatreName, String screenName, String city, Instant startTime, Instant endTime,
                              String language, String format, List<SeatLine> seats, BigDecimal totalAmount,
                              Instant createdAt, Instant cancelledAt, String cancellationReason, Instant checkedInAt,
                              String ticketToken, PaymentView payment, String customerEmail) {
    }

    public record CancellationQuoteView(boolean cancellable, String reason, int refundPercent,
                                        BigDecimal refundAmount) {
    }

    public record CancelRequest(@Size(max = 40) String reason) {
    }

    public record TicketValidationRequest(@NotBlank(message = "Enter or scan a ticket code") @Size(max = 64) String code) {
    }

    public record TicketValidationResult(boolean valid, String reference, String customerName, String movieTitle,
                                         String theatreName, String screenName, Instant startTime,
                                         List<String> seats, Instant checkedInAt) {
    }

    public record CancellationRuleRequest(
            @Min(value = 0, message = "Hours cannot be negative") @Max(8760) int minHoursBefore,
            @Min(value = 0, message = "Use 0 to 100") @Max(value = 100, message = "Use 0 to 100") int refundPercent) {
    }

    public record CancellationRulesRequest(@NotNull @Size(max = 10) @Valid List<CancellationRuleRequest> rules) {
    }

    public record CancellationRuleView(int minHoursBefore, int refundPercent) {
    }
}
