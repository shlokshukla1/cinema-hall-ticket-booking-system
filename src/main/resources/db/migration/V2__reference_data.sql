-- Reference data every environment needs. Demo users, movies and shows come from DemoDataSeeder.

INSERT INTO seat_categories (name, default_price, sort_order) VALUES
    ('Recliner', 450.00, 1),
    ('Premium', 280.00, 2),
    ('Standard', 180.00, 3);

INSERT INTO genres (name) VALUES
    ('Action'), ('Adventure'), ('Animation'), ('Comedy'), ('Drama'),
    ('Family'), ('Mystery'), ('Romance'), ('Sci-Fi'), ('Thriller');

-- Default policy: full refund 24h+ before the show, half 4h+ before, closed after that.
INSERT INTO cancellation_rules (min_hours_before, refund_percent) VALUES
    (24, 100),
    (4, 50);
