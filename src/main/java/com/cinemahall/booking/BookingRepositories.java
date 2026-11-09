package com.cinemahall.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class BookingRepositories {

    private BookingRepositories() {
    }

    public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

        Optional<SeatHold> findByReference(String reference);

        @Query("select h.showId from SeatHold h where h.reference = :reference")
        Optional<Long> findShowIdByReference(@Param("reference") String reference);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select h from SeatHold h where h.reference = :reference")
        Optional<SeatHold> lockByReference(@Param("reference") String reference);

        @Query("""
                select h from SeatHold h
                where h.userId = :userId and h.showId = :showId and h.status = com.cinemahall.booking.Enums.HoldStatus.ACTIVE""")
        List<SeatHold> findActive(@Param("userId") long userId, @Param("showId") long showId);

        @Query("""
                select h from SeatHold h
                where h.showId = :showId and h.status = com.cinemahall.booking.Enums.HoldStatus.ACTIVE""")
        List<SeatHold> findActiveForShow(@Param("showId") long showId);

        @Query("""
                select h.id from SeatHold h
                where h.status = com.cinemahall.booking.Enums.HoldStatus.ACTIVE and h.expiresAt <= :now
                order by h.id""")
        List<Long> findOverdueIds(@Param("now") Instant now, Pageable limit);

        /** Re-checks ACTIVE, so a hold confirmed in the same instant is left alone. */
        @Modifying
        @Query("""
                update SeatHold h set h.status = com.cinemahall.booking.Enums.HoldStatus.EXPIRED
                where h.id in :ids and h.status = com.cinemahall.booking.Enums.HoldStatus.ACTIVE""")
        int markExpired(@Param("ids") Collection<Long> ids);
    }

    public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

        Optional<Booking> findByReference(String reference);

        Optional<Booking> findByTicketToken(String ticketToken);

        @Query("select b from Booking b where b.user.id = :userId and b.idempotencyKey = :key")
        Optional<Booking> findByIdempotency(@Param("userId") long userId, @Param("key") String key);

        @Query("select b.user.id from Booking b where b.reference = :reference")
        Optional<Long> findOwnerId(@Param("reference") String reference);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select b from Booking b where b.reference = :reference")
        Optional<Booking> lockByReference(@Param("reference") String reference);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("""
                select b from Booking b
                where b.show.id = :showId and b.status = com.cinemahall.booking.Enums.BookingStatus.CONFIRMED""")
        List<Booking> lockConfirmedForShow(@Param("showId") long showId);

        /** Atomic check-in: of any number of simultaneous scans, exactly one updates a row. */
        @Modifying(clearAutomatically = true)
        @Query("""
                update Booking b set b.checkedInAt = :now, b.checkedInBy = :staffId
                where b.id = :id and b.status = com.cinemahall.booking.Enums.BookingStatus.CONFIRMED
                  and b.checkedInAt is null""")
        int checkIn(@Param("id") long id, @Param("staffId") long staffId, @Param("now") Instant now);
    }

    public interface PaymentRepository extends JpaRepository<Payment, Long> {
        Optional<Payment> findByBookingId(long bookingId);

        List<Payment> findByBookingIdIn(Collection<Long> bookingIds);
    }

    public interface CancellationRuleRepository extends JpaRepository<CancellationRule, Long> {
        List<CancellationRule> findAllByOrderByMinHoursBeforeDesc();
    }
}
