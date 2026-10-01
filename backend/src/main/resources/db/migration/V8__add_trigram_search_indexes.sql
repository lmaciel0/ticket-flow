-- Text search (q=) runs lower(title) LIKE '%term%' OR lower(description) LIKE '%term%'.
-- A leading % cannot use a B-tree index; trigram (GIN) indexes can. The indexed expressions must be
-- exactly the ones in the query (lower(...)), or PostgreSQL ignores the index.
-- No CONCURRENTLY: Flyway runs each migration in a transaction, and the table is small. On a large
-- production table, build them CONCURRENTLY in a non-transactional migration instead.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_tickets_title_trgm ON tickets USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_tickets_description_trgm ON tickets USING gin (lower(description) gin_trgm_ops);
