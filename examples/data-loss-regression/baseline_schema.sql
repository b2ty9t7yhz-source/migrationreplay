CREATE TABLE accounts (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('active', 'inactive'))
);

CREATE INDEX idx_accounts_status ON accounts(status);
