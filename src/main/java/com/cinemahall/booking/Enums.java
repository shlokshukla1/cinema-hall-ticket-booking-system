package com.cinemahall.booking;

/** Small state and option enums for the booking module. */
public final class Enums {

    private Enums() {
    }

    public enum HoldStatus { ACTIVE, CONFIRMED, RELEASED, EXPIRED }

    public enum BookingStatus { CONFIRMED, CANCELLED }

    public enum PaymentMethod { CARD, UPI, WALLET }

    public enum PaymentStatus { CAPTURED }

    public enum RefundStatus { NONE, NOT_ELIGIBLE, SIMULATED_REFUNDED }

    /** Payment is simulated: the demo chooses the outcome so that the failure path can be exercised. */
    public enum SimulatedOutcome { SUCCESS, DECLINE }
}
