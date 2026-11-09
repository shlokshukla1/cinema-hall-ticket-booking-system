package com.cinemahall.show;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long>, JpaSpecificationExecutor<Show> {

    /**
     * Row lock taken by confirmation, reschedule and show cancellation, so a booking cannot commit
     * halfway through a show being cancelled.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Show s where s.id = :id")
    Optional<Show> lockById(@Param("id") long id);

    @Query("""
            select count(s) > 0 from Show s
            where s.screen.id = :screenId
              and s.status = com.cinemahall.show.ShowStatus.SCHEDULED
              and s.startTime < :latestEnd and s.endTime > :earliestStart
              and (:excludeId = -1L or s.id <> :excludeId)""")
    boolean existsOverlap(@Param("screenId") long screenId,
                          @Param("earliestStart") Instant earliestStart,
                          @Param("latestEnd") Instant latestEnd,
                          @Param("excludeId") long excludeId);
}
