-- First-response SLA. Nullable: tickets created before this metric have no deadline and show no indicator.
ALTER TABLE tickets ADD COLUMN first_response_due_at TIMESTAMPTZ;
ALTER TABLE tickets ADD COLUMN first_responded_at TIMESTAMPTZ;
