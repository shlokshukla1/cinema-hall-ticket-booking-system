# Database schema

The schema is created by the Flyway migrations in [src/main/resources/db/migration](../src/main/resources/db/migration). A rendered copy of this diagram is at [er-diagram.png](er-diagram.png).

```mermaid
erDiagram
    GENRES ||--o{ MOVIE_GENRES : tags
    MOVIES ||--o{ MOVIE_GENRES : has
    MOVIES ||--o{ SHOWS : "screened in"
    THEATRES ||--o{ SCREENS : contains
    SCREENS ||--o{ SEATS : "laid out with"
    SEAT_CATEGORIES ||--o{ SEATS : prices
    SCREENS ||--o{ SHOWS : hosts
    USERS ||--o{ SEAT_HOLDS : places
    USERS ||--o{ BOOKINGS : owns
    SHOWS ||--o{ SHOW_SEATS : sells
    SEATS ||--o{ SHOW_SEATS : "offered as"
    SHOWS ||--o{ SEAT_HOLDS : "held for"
    SEAT_HOLDS |o--o{ SHOW_SEATS : "claims while HELD"
    SHOWS ||--o{ BOOKINGS : "sold for"
    SEAT_HOLDS ||--o| BOOKINGS : "confirmed as"
    BOOKINGS |o--o{ SHOW_SEATS : "owns while BOOKED"
    BOOKINGS ||--|{ BOOKING_SEATS : lists
    SHOW_SEATS ||--o{ BOOKING_SEATS : "history of"
    BOOKINGS ||--|| PAYMENTS : "paid by"

    USERS {
        bigint id PK
        varchar email UK
        varchar password_hash "BCrypt"
        varchar full_name
        varchar role "CUSTOMER, STAFF, ADMIN"
    }
    GENRES {
        bigint id PK
        varchar name UK
    }
    MOVIES {
        bigint id PK
        varchar title
        int duration_minutes "1 to 600"
        varchar language
        varchar certificate
        date release_date
        boolean active "archived when false"
        varchar poster_file "uploaded image, optional"
    }
    MOVIE_GENRES {
        bigint movie_id PK, FK
        bigint genre_id PK, FK
    }
    THEATRES {
        bigint id PK
        varchar name "UK with city"
        varchar city
        varchar address
        boolean active
    }
    SCREENS {
        bigint id PK
        bigint theatre_id FK
        varchar name "UK with theatre"
        boolean active
    }
    SEAT_CATEGORIES {
        bigint id PK
        varchar name UK
        numeric default_price
        int sort_order
    }
    SEATS {
        bigint id PK
        bigint screen_id FK
        bigint category_id FK
        varchar row_label "UK with screen and number"
        int seat_number
        boolean active
    }
    SHOWS {
        bigint id PK
        bigint movie_id FK
        bigint screen_id FK
        timestamptz start_time
        timestamptz end_time
        varchar language
        varchar format
        varchar status "SCHEDULED, CANCELLED"
    }
    SHOW_SEATS {
        bigint id PK
        bigint show_id FK "UK with seat_id"
        bigint seat_id FK
        numeric price "fixed when scheduled"
        varchar status "AVAILABLE, HELD, BOOKED"
        bigint hold_id FK "set only while HELD"
        timestamptz hold_expires_at
        bigint booking_id FK "set only while BOOKED"
    }
    SEAT_HOLDS {
        bigint id PK
        varchar reference UK
        bigint user_id FK
        bigint show_id FK
        varchar status "ACTIVE, CONFIRMED, RELEASED, EXPIRED"
        int seat_count
        numeric total_amount
        timestamptz expires_at
    }
    BOOKINGS {
        bigint id PK
        varchar reference UK "CH-XXXXXXXX"
        varchar ticket_token UK "QR content"
        bigint user_id FK "UK with idempotency_key"
        bigint show_id FK
        bigint hold_id FK, UK
        varchar idempotency_key
        varchar status "CONFIRMED, CANCELLED"
        numeric total_amount
        timestamptz cancelled_at
        timestamptz checked_in_at
        bigint checked_in_by FK
    }
    BOOKING_SEATS {
        bigint id PK
        bigint booking_id FK
        bigint show_seat_id FK
        varchar seat_label "snapshot"
        varchar category_name "snapshot"
        numeric price "snapshot"
        bigint active_show_seat_id UK "NULL once cancelled"
    }
    PAYMENTS {
        bigint id PK
        bigint booking_id FK, UK
        varchar provider_ref UK
        varchar method "CARD, UPI, WALLET"
        numeric amount
        varchar status "CAPTURED"
        numeric refund_amount
        int refund_percent
        varchar refund_status "NONE, NOT_ELIGIBLE, SIMULATED_REFUNDED"
    }
    CANCELLATION_RULES {
        bigint id PK
        int min_hours_before UK
        int refund_percent "0 to 100"
    }
    AUDIT_LOGS {
        bigint id PK
        bigint actor_id "NULL for system"
        varchar actor_email
        varchar action
        varchar entity_type
        varchar entity_id
        varchar details
        timestamptz created_at
    }
```

`CANCELLATION_RULES` and `AUDIT_LOGS` stand alone. An audit row keeps a copy of the actor and record identifiers, so it stays readable whatever later happens to those records.

## Constraints that protect the booking rules

| Constraint | What it guarantees |
|---|---|
| `show_seats UNIQUE (show_id, seat_id)` | A show has exactly one sellable row per seat. |
| `show_seats` state `CHECK` | `HELD` requires a hold and an expiry; `BOOKED` requires a booking; `AVAILABLE` allows neither. Half-applied states cannot be stored. |
| `booking_seats UNIQUE (active_show_seat_id)` | Two live bookings can never own one seat. The column is cleared on cancellation, so the seat can be resold while its history stays. |
| `bookings UNIQUE (hold_id)` | A hold can become at most one booking. |
| `bookings UNIQUE (user_id, idempotency_key)` | A retried confirmation cannot create a second booking. |
| `bookings CHECK (cancelled_at IS NULL OR checked_in_at IS NULL)` | A ticket cannot be both used and cancelled. |
| `payments UNIQUE (booking_id)`, refund `CHECK`s | One payment per booking, and a refund can never exceed the amount paid. |

Indexes cover the common lookups: shows by movie, screen and start time; show seats by status, hold and booking; holds by expiry; bookings by user, show and date; and audit rows by date, action and record.
