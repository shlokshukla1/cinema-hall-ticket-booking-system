package com.cinemahall.booking;

import com.cinemahall.booking.Enums.PaymentMethod;
import com.cinemahall.booking.Enums.SimulatedOutcome;
import com.cinemahall.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Simulated gateway: no network call and no card data. It is the one class a real provider
 * integration would replace.
 */
@Component
public class PaymentGateway {

    public static class PaymentDeclinedException extends ApiException {
        public PaymentDeclinedException() {
            super(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED",
                    "The simulated payment was declined. Your seats are still held, so you can try again.");
        }
    }

    /** @return the provider's payment reference */
    public String charge(BigDecimal amount, PaymentMethod method, SimulatedOutcome outcome) {
        if (outcome == SimulatedOutcome.DECLINE) {
            throw new PaymentDeclinedException();
        }
        return "SIM-" + UUID.randomUUID();
    }
}
