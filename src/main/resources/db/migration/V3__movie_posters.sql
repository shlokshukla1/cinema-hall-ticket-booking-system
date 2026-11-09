-- Name of an uploaded poster file in the configured poster folder. NULL means the default artwork or cover.
ALTER TABLE movies ADD COLUMN poster_file VARCHAR(100);
