-- A single row remembering when the demo data was last reset.
CREATE TABLE demo_state (
    id            SMALLINT PRIMARY KEY CHECK (id = 1),
    last_reset_at TIMESTAMPTZ NOT NULL
);
