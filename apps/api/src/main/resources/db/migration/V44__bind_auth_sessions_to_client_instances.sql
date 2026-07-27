-- MySQL compatibility migration. Legacy rows remain explicitly unbound and
-- are denied by repository predicates until a new session/challenge is issued.
ALTER TABLE auth_session
    ADD COLUMN client_instance_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER csrf_hash,
    ADD COLUMN client_instance_bound TINYINT(1) NOT NULL DEFAULT 0
        AFTER client_instance_hash,
    ADD CONSTRAINT chk_auth_session_client_instance_binding
        CHECK (
            (client_instance_bound = 0 AND client_instance_hash IS NULL)
            OR (client_instance_bound = 1 AND client_instance_hash IS NOT NULL)
        );

ALTER TABLE auth_webauthn_challenge
    ADD COLUMN client_instance_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER request_json,
    ADD COLUMN client_instance_bound TINYINT(1) NOT NULL DEFAULT 0
        AFTER client_instance_hash,
    ADD CONSTRAINT chk_auth_webauthn_challenge_client_instance_binding
        CHECK (
            (client_instance_bound = 0 AND client_instance_hash IS NULL)
            OR (client_instance_bound = 1 AND client_instance_hash IS NOT NULL)
        );

ALTER TABLE auth_email_challenge
    ADD COLUMN client_instance_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER codigo_digest,
    ADD COLUMN client_instance_bound TINYINT(1) NOT NULL DEFAULT 0
        AFTER client_instance_hash,
    ADD CONSTRAINT chk_auth_email_challenge_client_instance_binding
        CHECK (
            (client_instance_bound = 0 AND client_instance_hash IS NULL)
            OR (client_instance_bound = 1 AND client_instance_hash IS NOT NULL)
        );
