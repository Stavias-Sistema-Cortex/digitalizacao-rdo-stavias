ALTER TABLE auth_password_credential
    ADD COLUMN auth_epoch bigint NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_auth_password_credential_epoch
        CHECK (auth_epoch >= 1);

CREATE OR REPLACE FUNCTION cortex_bump_password_epoch_on_identity_revoke_v88()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ATIVA' AND NEW.status <> 'ATIVA' THEN
        UPDATE auth_password_credential
        SET auth_epoch = auth_epoch + 1
        WHERE colaborador_id = NEW.colaborador_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_auth_password_epoch_identity_revoke_v88
AFTER UPDATE OF status ON auth_identity
FOR EACH ROW
EXECUTE FUNCTION cortex_bump_password_epoch_on_identity_revoke_v88();

CREATE OR REPLACE FUNCTION cortex_bump_password_epoch_on_collaborator_revoke_v88()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF (
        (OLD.ativo = TRUE AND NEW.ativo = FALSE)
        OR (OLD.deletado_em IS NULL AND NEW.deletado_em IS NOT NULL)
    ) AND EXISTS (
        SELECT 1
        FROM auth_identity identity
        WHERE identity.colaborador_id = NEW.id
          AND identity.status = 'ATIVA'
    ) THEN
        UPDATE auth_password_credential
        SET auth_epoch = auth_epoch + 1
        WHERE colaborador_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_auth_password_epoch_collaborator_revoke_v88
AFTER UPDATE OF ativo, deletado_em ON colaborador
FOR EACH ROW
EXECUTE FUNCTION cortex_bump_password_epoch_on_collaborator_revoke_v88();
