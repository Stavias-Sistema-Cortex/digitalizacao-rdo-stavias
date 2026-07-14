-- Bounded retention jobs rely on these leading timestamp columns. No rows are
-- rewritten here; cleanup remains asynchronous and auditable through logs.
CREATE INDEX idx_auth_session_expiry_retention
    ON auth_session (expira_em, id);
CREATE INDEX idx_auth_session_revoked_retention
    ON auth_session (revogado_em, id);
CREATE INDEX idx_auth_webauthn_challenge_retention
    ON auth_webauthn_challenge (expira_em, id);
