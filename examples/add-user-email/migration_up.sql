CREATE TABLE user_email_lookup (
    user_id INTEGER PRIMARY KEY,
    normalized_email TEXT NOT NULL UNIQUE,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

INSERT INTO user_email_lookup (user_id, normalized_email)
SELECT id, lower(email)
FROM users;

CREATE UNIQUE INDEX idx_user_email_lookup_normalized
ON user_email_lookup(normalized_email);
