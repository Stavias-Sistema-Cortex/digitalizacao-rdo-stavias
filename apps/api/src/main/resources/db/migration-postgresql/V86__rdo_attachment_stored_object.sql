-- A foto do RDO ganha morada no servidor.
--
-- O anexo de RDO sempre foi só ficha: nome, tamanhos e um storage_ref
-- sintético ("indexeddb:" + id) apontando para o IndexedDB do aparelho que
-- fotografou — um endereço que nenhum outro aparelho resolve. O binário passa
-- a morar no ObjectStorage sob stored_object, como os anexos de mensagem, e
-- esta coluna é o vínculo. Fica nula enquanto o upload não aconteceu: a ficha
-- continua subindo primeiro, e o vínculo chega quando o binário chegar.
ALTER TABLE rdo_attachment
    ADD COLUMN stored_object_id varchar(36) NULL REFERENCES stored_object (id);

CREATE INDEX idx_rdo_attachment_stored_object
    ON rdo_attachment (stored_object_id)
    WHERE stored_object_id IS NOT NULL;
