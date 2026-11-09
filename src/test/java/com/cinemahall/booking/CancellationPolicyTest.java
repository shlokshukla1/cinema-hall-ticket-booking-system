package com.cinemahall.booking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final List<CancellationRule> RULES = List.of(new CancellationRule(4, 50), new CancellationRule(24, 100));

    @ParameterizedTest(name = "{0} minutes before: cancellable={1}, refund={2}%")
    @CsvSource({
            "2880, true, 100",   // two days
            "1440, true, 100",   // exactly 24h: boundary is inclusive
            "1439, true, 50",    // one minute short of 24h
            "240,  true, 50",    // exactly 4h
            "239,  false, 0",    // one minute short of 4h
            "1,    false, 0",
            "0,    false, 0",    // show starting now
            "-60,  false, 0"     // already started
    })
    void appliesTheMostGenerousRuleWhoseNoticeIsMet(long minutes, boolean cancellable, int percent) {
        CancellationPolicy.Quote quote = CancellationPolicy.quote(RULES, NOW, NOW.plus(Duration.ofMinutes(minutes)),
                new BigDecimal("200.00"));

        assertThat(quote.cancellable()).isEqualTo(cancellable);
        assertThat(quote.refundPercent()).isEqualTo(percent);
    }

    @Test
    void noRulesMeansNoCancellation() {
        assertThat(CancellationPolicy.quote(List.of(), NOW, NOW.plus(Duration.ofDays(30)), BigDecimal.TEN).cancellable())
                .isFalse();
    }

    @Test
    void aZeroPercentRuleAllowsCancellingWithoutARefund() {
        CancellationPolicy.Quote quote = CancellationPolicy.quote(List.of(new CancellationRule(0, 0)), NOW,
                NOW.plus(Duration.ofHours(1)), new BigDecimal("99.00"));

        assertThat(quote.cancellable()).isTrue();
        assertThat(quote.refundAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void refundsRoundHalfUpToWholePaise() {
        assertThat(CancellationPolicy.percentOf(new BigDecimal("100.01"), 50)).isEqualByComparingTo("50.01");
        assertThat(CancellationPolicy.percentOf(new BigDecimal("33.33"), 33)).isEqualByComparingTo("11.00");
    }
}
