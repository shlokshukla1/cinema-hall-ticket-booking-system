package com.cinemahall.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring Data repositories for the catalogue module, grouped because each is only a few lines. */
public final class CatalogRepositories {

    private CatalogRepositories() {
    }

    public interface GenreRepository extends JpaRepository<Genre, Long> {
        Optional<Genre> findByName(String name);
    }

    public interface MovieRepository extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {
        @Query("""
                select count(s) > 0 from Show s
                where s.movie.id = :movieId
                  and s.status = com.cinemahall.show.ShowStatus.SCHEDULED and s.startTime > :now""")
        boolean hasUpcomingShows(@Param("movieId") long movieId, @Param("now") Instant now);
    }

    public interface TheatreRepository extends JpaRepository<Theatre, Long>, JpaSpecificationExecutor<Theatre> {
        @Query("""
                select count(s) > 0 from Show s
                where s.screen.theatre.id = :theatreId
                  and s.status = com.cinemahall.show.ShowStatus.SCHEDULED and s.startTime > :now""")
        boolean hasUpcomingShows(@Param("theatreId") long theatreId, @Param("now") Instant now);
    }

    public interface ScreenRepository extends JpaRepository<Screen, Long> {
        List<Screen> findByTheatreIdOrderByName(long theatreId);

        /** Serialises scheduling per screen, so two admins cannot create overlapping shows concurrently. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from Screen s where s.id = :id")
        Optional<Screen> lockById(@Param("id") long id);

        @Query("""
                select count(s) > 0 from Show s
                where s.screen.id = :screenId
                  and s.status = com.cinemahall.show.ShowStatus.SCHEDULED and s.startTime > :now""")
        boolean hasUpcomingShows(@Param("screenId") long screenId, @Param("now") Instant now);

        @Query("select count(s) > 0 from Show s where s.screen.id = :screenId")
        boolean hasAnyShows(@Param("screenId") long screenId);
    }

    public interface SeatCategoryRepository extends JpaRepository<SeatCategory, Long> {
        List<SeatCategory> findAllByOrderBySortOrderAscNameAsc();
    }

    public interface SeatRepository extends JpaRepository<Seat, Long> {
        @Query("select s from Seat s join fetch s.category where s.screen.id = :screenId")
        List<Seat> findLayout(@Param("screenId") long screenId);

        @Query("select s from Seat s join fetch s.category where s.screen.id = :screenId and s.active = true")
        List<Seat> findActiveLayout(@Param("screenId") long screenId);

        @Query("select s.screen.id as screenId, count(s) as seatCount from Seat s "
                + "where s.active = true and s.screen.id in :screenIds group by s.screen.id")
        List<ScreenSeatCount> countActive(@Param("screenIds") List<Long> screenIds);

        @Modifying
        @Query("delete from Seat s where s.screen.id = :screenId")
        void deleteByScreenId(@Param("screenId") long screenId);
    }

    public interface ScreenSeatCount {
        Long getScreenId();

        long getSeatCount();
    }
}
