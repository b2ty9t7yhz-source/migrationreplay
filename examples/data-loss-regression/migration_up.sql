DELETE FROM accounts WHERE status = 'inactive';

ALTER TABLE accounts ADD COLUMN migrated_at TEXT;
