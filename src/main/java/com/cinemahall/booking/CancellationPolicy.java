package com.cinemahall.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure refund calculation with no framework or database, so every boundary is easy to unit test. */
public final class CancellationPolicy {

    private CancellationPolicy() {
    }

    public record Quote(boolean cancellable, int refundPercent, BigDecimal refundAmount) {
    }

    /** Applies the most generous rule whose minimum notice is met. If none is met, cancelling is closed. */
    public static Quote quote(List<CancellationRule> rules, Instant now, Instant showStart, BigDecimal total) {
        long minutesUntilShow = Duration.between(now, showStart).toMinutes();
        if (!showStart.isAfter(now)) {
            return new Quote(false, 0, BigDecimal.ZERO.setScale(2));
        }
        Optional<CancellationRule> applicable = rules.stream()
                .filter(rule -> minutesUntilShow >= rule.getMinHoursBefore() * 60L)
                .max(Comparator.comparingInt(CancellationRule::getMinHoursBefore));
        if (applicable.isEmpty()) {
            return new Quote(false, 0, BigDecimal.ZERO.setScale(2));
        }
        int percent = applicable.get().getRefundPercent();
        return new Quote(true, percent, percentOf(total, percent));
    }

    public static BigDecimal percentOf(BigDecimal amount, int percent) {
        return amount.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
