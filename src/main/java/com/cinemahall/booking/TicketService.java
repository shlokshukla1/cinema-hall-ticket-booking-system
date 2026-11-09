package com.cinemahall.booking;

import com.cinemahall.audit.AuditService;
import com.cinemahall.auth.AuthUser;
import com.cinemahall.booking.BookingDtos.TicketValidationResult;
import com.cinemahall.booking.BookingRepositories.BookingRepository;
import com.cinemahall.booking.Enums.BookingStatus;
import com.cinemahall.common.ApiException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TicketService {

    private static final int QR_SIZE_PX = 320;

    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final AuditService audit;
    private final Clock clock;

    public TicketService(BookingRepository bookings, BookingService bookingService, AuditService audit, Clock clock) {
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public byte[] qrPng(AuthUser caller, String reference) {
        Booking booking = bookingService.findVisible(caller, reference);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("BOOKING_CANCELLED", "A cancelled booking has no ticket");
        }
        try (ByteArrayOutputStream png = new ByteArrayOutputStream()) {
            BitMatrix matrix = new QRCodeWriter().encode(booking.getTicketToken(), BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return png.toByteArray();
        } catch (WriterException | IOException ex) {
            throw new IllegalStateException("Could not render QR code", ex);
        }
    }

    /**
     * Simulated entry gate; accepts the QR token or the printed reference. Rejections are ApiExceptions but must
     * still leave an audit row, hence noRollbackFor. Only the conditional UPDATE changes ticket state, and a
     * rejection means that UPDATE did not run or matched nothing, so there is nothing to undo.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TicketValidationResult validate(AuthUser staff, String rawCode) {
        String code = rawCode.trim();
        Instant now = Instant.now(clock);
        Optional<Booking> found = bookings.findByTicketToken(code)
                .or(() -> bookings.findByReference(code.toUpperCase(Locale.ROOT)));
        if (found.isEmpty()) {
            audit.record(staff, "TICKET_REJECTED", "TICKET", "unknown", "reason=NOT_FOUND");
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND",
                    "No ticket matches this code");
        }
        Booking booking = found.get();
        String reference = booking.getReference();
        long bookingId = booking.getId();

        if (booking.getStatus() == BookingStatus.CONFIRMED && booking.getCheckedInAt() == null
                && !booking.getShow().getEndTime().isAfter(now)) {
            throw reject(staff, reference, "TICKET_EXPIRED", "This show has already finished");
        }

        if (bookings.checkIn(bookingId, staff.id(), now) != 1) {
            // Lost the race or was never eligible. checkIn cleared the session, so this re-read is current.
            Booking current = bookings.findById(bookingId).orElseThrow();
            if (current.getStatus() == BookingStatus.CANCELLED) {
                throw reject(staff, reference, "TICKET_CANCELLED", "This booking was cancelled");
            }
            throw reject(staff, reference, "TICKET_ALREADY_USED",
                    "This ticket was already used at " + current.getCheckedInAt());
        }

        Booking admitted = bookings.findById(bookingId).orElseThrow();
        audit.record(staff, "TICKET_VALIDATED", "BOOKING", reference, "admitted");
        List<String> seats = admitted.getSeats().stream().map(BookingSeat::getSeatLabel).toList();
        return new TicketValidationResult(true, reference, admitted.getUser().getFullName(),
                admitted.getShow().getMovie().getTitle(), admitted.getShow().getScreen().getTheatre().getName(),
                admitted.getShow().getScreen().getName(), admitted.getShow().getStartTime(), seats,
                admitted.getCheckedInAt());
    }

    private ApiException reject(AuthUser staff, String reference, String code, String message) {
        audit.record(staff, "TICKET_REJECTED", "BOOKING", reference, "reason=" + code);
        return ApiException.conflict(code, message);
    }
}
