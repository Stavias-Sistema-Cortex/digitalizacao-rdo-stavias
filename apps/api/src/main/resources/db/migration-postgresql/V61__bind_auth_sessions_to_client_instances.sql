-- Client instance proofs are per-tab and are never persisted in raw form.
-- Existing records deliberately remain unbound so V61 repository queries deny
-- them instead of allowing a shared-origin cookie to cross a tab boundary.
ALTER TABLE auth_session
    ADD COLUMN client_instance_hash char(64),
    ADD COLUMN client_instance_bound boolean NOT NULL DEFAULT false;

ALTER TABLE auth_session
    ADD CONSTRAINT chk_auth_session_client_instance_binding
    CHECK (
        (client_instance_bound = false AND client_instance_hash IS NULL)
        OR (
            client_instance_bound = true
            AND client_instance_hash IS NOT NULL
            AND client_instance_hash ~ '^[0-9a-f]{64}$'
        )
    );

ALTER TABLE auth_webauthn_challenge
    ADD COLUMN client_instance_hash char(64),
    ADD COLUMN client_instance_bound boolean NOT NULL DEFAULT false;

ALTER TABLE auth_webauthn_challenge
    ADD CONSTRAINT chk_auth_webauthn_challenge_client_instance_binding
    CHECK (
        (client_instance_bound = false AND client_instance_hash IS NULL)
        OR (
            client_instance_bound = true
            AND client_instance_hash IS NOT NULL
            AND client_instance_hash ~ '^[0-9a-f]{64}$'
        )
    );

ALTER TABLE auth_email_challenge
    ADD COLUMN client_instance_hash char(64),
    ADD COLUMN client_instance_bound boolean NOT NULL DEFAULT false;

ALTER TABLE auth_email_challenge
    ADD CONSTRAINT chk_auth_email_challenge_client_instance_binding
    CHECK (
        (client_instance_bound = false AND client_instance_hash IS NULL)
        OR (
            client_instance_bound = true
            AND client_instance_hash IS NOT NULL
            AND client_instance_hash ~ '^[0-9a-f]{64}$'
        )
    );
