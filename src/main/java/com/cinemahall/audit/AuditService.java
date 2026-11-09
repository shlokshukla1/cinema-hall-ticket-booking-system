package com.cinemahall.audit;

import com.cinemahall.auth.AuthUser;
import com.cinemahall.common.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AuditService {

    private static final int MAX_DETAILS = 1000;

    private final AuditLogRepository repository;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Joins the caller's transaction, so an action and its audit row commit or roll back together.
     * Failed attempts are recorded after the rollback, in a transaction of their own.
     */
    @Transactional
    public void record(AuthUser actor, String action, String entityType, Object entityId, String details) {
        Long actorId = actor == null ? null : actor.id();
        String actorEmail = actor == null ? "system" : actor.email();
        String trimmed = details != null && details.length() > MAX_DETAILS ? details.substring(0, MAX_DETAILS) : details;
        repository.save(new AuditLog(actorId, actorEmail, action, entityType, String.valueOf(entityId), trimmed,
                Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> search(String action, String entityType, String actor, Instant from, Instant to,
                                           int page, int size) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (StringUtils.hasText(action)) {
                where.add(cb.equal(root.get("action"), action.trim().toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(entityType)) {
                where.add(cb.equal(root.get("entityType"), entityType.trim().toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(actor)) {
                where.add(cb.like(cb.lower(root.get("actorEmail")), "%" + actor.trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.<Instant>get("createdAt"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return PageResponse.of(
                repository.findAll(spec, PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "id"))),
                AuditEntry::from);
    }

    public record AuditEntry(Long id, String actorEmail, String action, String entityType, String entityId,
                             String details, Instant createdAt) {
        static AuditEntry from(AuditLog log) {
            return new AuditEntry(log.getId(), log.getActorEmail(), log.getAction(), log.getEntityType(),
                    log.getEntityId(), log.getDetails(), log.getCreatedAt());
        }
    }
}
