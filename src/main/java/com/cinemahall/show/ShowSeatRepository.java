package com.cinemahall.show;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The conditional UPDATEs below are the heart of double-booking prevention. Each checks the current state in its
 * WHERE clause and the database re-evaluates that after waiting for any row lock, so of two racing requests exactly
 * one sees "1 row updated". The caller treats anything else as a conflict and rolls back.
 */
public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    @Modifying
    @Query("""
            update ShowSeat s
            set s.status = com.cinemahall.show.ShowSeatStatus.HELD, s.holdId = :holdId, s.holdExpiresAt = :expiresAt
            where s.show.id = :showId and s.seat.id = :seatId
              and (s.status = com.cinemahall.show.ShowSeatStatus.AVAILABLE
                   or (s.status = com.cinemahall.show.ShowSeatStatus.HELD and s.holdExpiresAt <= :now))""")
    int holdIfFree(@Param("showId") long showId, @Param("seatId") long seatId, @Param("holdId") long holdId,
                   @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    /** Only succeeds for seats this hold still owns and whose hold has not expired. */
    @Modifying
    @Query("""
            update ShowSeat s
            set s.status = com.cinemahall.show.ShowSeatStatus.BOOKED, s.bookingId = :bookingId,
                s.holdId = null, s.holdExpiresAt = null
            where s.holdId = :holdId and s.status = com.cinemahall.show.ShowSeatStatus.HELD
              and s.holdExpiresAt > :now""")
    int bookHeldSeats(@Param("holdId") long holdId, @Param("bookingId") long bookingId, @Param("now") Instant now);

    @Modifying
    @Query("""
            update ShowSeat s
            set s.status = com.cinemahall.show.ShowSeatStatus.AVAILABLE, s.holdId = null, s.holdExpiresAt = null
            where s.holdId in :holdIds and s.status = com.cinemahall.show.ShowSeatStatus.HELD""")
    int releaseHeldSeats(@Param("holdIds") Collection<Long> holdIds);

    @Modifying
    @Query("""
            update ShowSeat s
            set s.status = com.cinemahall.show.ShowSeatStatus.AVAILABLE, s.bookingId = null
            where s.bookingId = :bookingId and s.status = com.cinemahall.show.ShowSeatStatus.BOOKED""")
    int releaseBookedSeats(@Param("bookingId") long bookingId);

    @Query("""
            select s from ShowSeat s join fetch s.seat st join fetch st.category
            where s.show.id = :showId""")
    List<ShowSeat> findMap(@Param("showId") long showId);

    @Query("""
            select s from ShowSeat s join fetch s.seat st join fetch st.category
            where s.show.id = :showId and st.id in :seatIds""")
    List<ShowSeat> findByShowAndSeats(@Param("showId") long showId, @Param("seatIds") Collection<Long> seatIds);

    @Query("""
            select s from ShowSeat s join fetch s.seat st join fetch st.category
            where s.holdId = :holdId and s.status = com.cinemahall.show.ShowSeatStatus.HELD""")
    List<ShowSeat> findHeldBy(@Param("holdId") long holdId);

    @Query("""
            select s.show.id as showId,
                   count(s) as total,
                   sum(case when s.status = com.cinemahall.show.ShowSeatStatus.AVAILABLE
                             or (s.status = com.cinemahall.show.ShowSeatStatus.HELD and s.holdExpiresAt <= :now)
                            then 1 else 0 end) as available,
                   sum(case when s.status = com.cinemahall.show.ShowSeatStatus.BOOKED then 1 else 0 end) as booked,
                   sum(case when s.status = com.cinemahall.show.ShowSeatStatus.HELD and s.holdExpiresAt > :now
                            then 1 else 0 end) as held,
                   min(s.price) as minPrice
            from ShowSeat s where s.show.id in :showIds group by s.show.id""")
    List<SeatCounts> countByShow(@Param("showIds") Collection<Long> showIds, @Param("now") Instant now);

    interface SeatCounts {
        Long getShowId();

        long getTotal();

        long getAvailable();

        long getBooked();

        long getHeld();

        BigDecimal getMinPrice();
    }
}
