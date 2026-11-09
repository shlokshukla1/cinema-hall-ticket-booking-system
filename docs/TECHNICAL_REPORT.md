# Technical report: Cinema Hall Ticket Booking System

**Candidate:** _(name and registration number)_ · **Role:** Full-Stack Web Development · **Organisation:** Algions AI Labs Private Limited

## 1. Summary

The application covers the whole journey for a cinema's customers, door staff and administrators. The hardest requirement is that a seat must never be sold twice, even when requests race, retry or arrive after a hold has expired. Most design decisions below follow from that requirement, and the database is the final authority on it.

The result is a Spring Boot modular monolith with a plain HTML, CSS and JavaScript frontend. All 88 automated tests pass on both H2 and PostgreSQL, including tests with real concurrent threads ([evidence](TEST_EVIDENCE.md)).

## 2. Architecture

**A modular monolith.** The brief says microservices are not required, and booking needs seats, holds, bookings and payments to change in one transaction. In one process with one database, that is a single local transaction rather than a distributed one.

**Layers.** Controllers handle HTTP and validation, services hold business rules and transaction boundaries, and repositories handle persistence. Controllers exchange DTO records and never expose entities, so a field such as `password_hash` cannot leak into a response by accident.

**Static frontend.** The pages hold no data, and every record comes from the API. The same API therefore serves the UI, Swagger and the tests, and hiding a button in the UI is never what enforces a rule.

**Schema ownership.** Flyway migrations own the schema, and Hibernate runs in `validate` mode. A mismatch between code and database stops startup rather than surfacing later as a runtime error.

## 3. Preventing double booking

All seat state lives on one row per seat per show (`show_seats`), with this lifecycle:

```text
AVAILABLE --hold--> HELD --confirm--> BOOKED --cancel--> AVAILABLE
                     |
                     +--release or expiry--> AVAILABLE
```

Protection comes in layers, so that a mistake in one does not sell a seat twice.

**1. Conditional updates.** State changes are `UPDATE` statements whose `WHERE` clause states the required current state:

```sql
UPDATE show_seats SET status = 'HELD', hold_id = ?, hold_expires_at = ?
WHERE show_id = ? AND seat_id = ?
  AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
```

The database re-checks the condition after waiting for any row lock, so when requests race for a seat, exactly one sees "1 row updated". Any other result rolls back the whole transaction, so a multi-seat selection is claimed completely or not at all.

This was preferred over reading the row and then writing it, which has a gap between the two steps, and over optimistic version retries, which would turn expected contention into retry loops.

**2. Lock ordering.** Seats are claimed in sorted ID order, and confirmation always locks show, then hold, then seats. Consistent ordering prevents the common deadlocks. If the database still reports a lock failure, the API returns `409 RESOURCE_BUSY` and nothing has been committed.

**3. Database constraints.** A unique constraint on `booking_seats.active_show_seat_id` makes two live owners of a seat impossible, and a `CHECK` makes half-applied states impossible. Tests bypass the application and write SQL directly to prove the database refuses both.

## 4. Other design decisions

**Hold expiry is enforced by timestamp, not by a job.** Every query compares `hold_expires_at` with the current time, so an overdue hold is already free for other customers and useless to its owner. The sweeper that runs every 15 seconds only tidies stored state. If it is late or stopped, no rule is broken.

**Idempotent confirmation.** `POST /api/bookings` requires an `Idempotency-Key`. The UI keeps one key per hold, including across reloads. A repeat returns the original booking with `200`, while the same key on a different hold returns `409`. A unique constraint backs this up, and the check runs after the locks are taken, so a concurrent duplicate waits and then sees the first result.

**Declined payments.** A decline rolls back everything, leaves the hold in place for a retry, and is audited after the rollback in its own transaction. `PaymentGateway` is the one class a real provider would replace. A real integration would also need to move the charge outside the database transaction, with a reconciliation step.

**Single-use tickets.** Check-in is a conditional update on `status = 'CONFIRMED' AND checked_in_at IS NULL`, so simultaneous scans admit exactly once. The QR code holds a 256-bit random token, which cannot be guessed, while the short reference is for humans at the box office.

**Cancellation.** Refunds come from rules stored in the database and editable by admins (by default, 100% at 24 hours or more, 50% at 4 hours or more, then closed). `CancellationPolicy` is a pure function, which makes its boundaries easy to test, and the quote shown to customers uses the same code as the cancellation. Cancelling a whole show refunds everyone in full, and the shared show lock stops a booking from committing halfway through.

**Stale-read bug found in review.** The first version of `cancel()` loaded the booking to check ownership and then locked it. Hibernate returns the already-loaded object from a locking query, so the state check could have used stale data. Ownership is now checked by a projection that loads no entity. `confirm()` follows the same rule.

**Reporting.** Figures are computed from live tables, so there is no copy to drift. Booked seats (from seat state) and active tickets (from booking records) are counted independently, and the report's "Reconciled" column shows whether they agree.

**Prices are snapshots.** A show fixes its seat prices when scheduled, and a booking copies the seat label, category and price. Later catalogue edits cannot rewrite what a customer bought.

**Time.** All rules read an injected `Clock`, so tests move time forward rather than sleeping. Timestamps are stored with a time zone, and show starts are normalised to whole minutes. That normalisation fixed a real test failure, where nanosecond input and microsecond storage made an exact boundary comparison round the wrong way.

## 5. Security

| Area | Approach |
|---|---|
| Authentication | Stateless HS256 JWT with a 2-hour life, BCrypt password hashes, and no signing key in the repository |
| Authorization | Role rules in the server's security filter chain. Ownership is checked in services, and another customer's records return `404`, so references cannot be probed. |
| Registration | Always creates a `CUSTOMER`; the role is never read from the request |
| Login abuse | Same message and similar timing for unknown emails and wrong passwords, with a 15-minute lockout after 5 failures |
| Injection | JPQL parameters and escaped `LIKE` wildcards on the server. The frontend writes data with `textContent` only and never builds HTML from it. |
| Browser | Content Security Policy with `script-src 'self'`, frame denial, and tokens sent in a header rather than cookies, which is why CSRF protection is off |
| File uploads | Admin only, with a 3 MB limit. The type is detected from the file's bytes, SVG is refused because it can carry scripts, and stored names are generated. |
| Sensitive data | No card data is collected, a cancelled ticket's token is withheld, and only dummy demo credentials exist |
| Audit | Append-only rows for material actions, including rejected ticket scans and declined payments. Successful actions' audit rows commit or roll back with the action itself. |

## 6. Testing

| Level | Coverage |
|---|---|
| Unit | Refund boundaries and rounding |
| Integration | Real HTTP layer, security filters, services and database through MockMvc. Includes real multi-threaded races for seats, confirmations and ticket scans. |
| Cross-database | The same suite on H2 and on an embedded PostgreSQL 14 server (`-Ppostgres`), because locking behaviour differs between engines |
| Browser | Customer, staff and admin journeys in Edge with the real CSP active, including a two-user conflict and phone-width checks, plus axe accessibility scans |

Browser testing found a bug the API tests could not: a success message raised while a dialog was open disappeared when the dialog closed.

## 7. Accessibility and responsiveness

Forms use real labels and show server errors beside their fields. The seat map has one tab stop with arrow-key movement, and each seat announces its row, category, price and state. State is never shown by colour alone: booked seats show ×, and held seats are hatched. The chart has a table view. Layouts work down to phone width, and wide tables scroll inside their own container.

## 8. Trade-offs and what would change for production

- **H2 as the default** favours a zero-install review. Production should use PostgreSQL, and the tests already run against it.
- **Per-show lock on confirmation** serialises confirmations for one show. Each is short, so this is fine at cinema scale, and it keeps show cancellation simple. Very high volume would call for a narrower approach.
- **A real payment provider** would need webhooks, a pending state and reconciliation.
- **Scaling beyond one instance** needs the login throttle moved to a shared store. The booking rules already hold across instances, because they live in the database. The sweeper is safe to run on several instances but would do redundant work.
- **Token revocation** would need short-lived tokens with refresh, or a server-side deny list.

All known limitations are listed in the [README](../README.md#known-limitations).

## 9. AI assistance

Claude Code (Anthropic, model Claude Opus 5.5) was used to generate the application code, tests and documentation from the assessment brief, and to run and debug them. _(Candidate: add how you reviewed and changed the work before submitting, and keep this section accurate.)_
