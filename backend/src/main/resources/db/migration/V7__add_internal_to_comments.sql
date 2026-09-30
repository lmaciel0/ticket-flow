-- Internal notes are visible only to agents and managers, never to the requester.
ALTER TABLE comments ADD COLUMN internal BOOLEAN NOT NULL DEFAULT FALSE;
