package com.projeto.cortex.auth.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OfflineGrantServiceTest {

    private static final UUID COLLABORATOR_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final Instant DATABASE_NOW =
            Instant.parse("2030-01-02T03:04:05.123456Z");
    private static final String OBRA_A =
            "20000000-0000-0000-0000-000000000001";
    private static final String OBRA_Z =
            "20000000-0000-0000-0000-000000000009";

    private final CurrentUserService currentUsers =
            mock(CurrentUserService.class);
    private final OfflineGrantSubjectRepository subjects =
            mock(OfflineGrantSubjectRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        when(subjects.findActive(COLLABORATOR_ID)).thenReturn(Optional.of(
                new OfflineGrantSubject("Pessoa Sintética", DATABASE_NOW)
        ));
    }

    @Test
    void betaGrantUsesDatabaseTimeSortedExplicitScopeAndVerifiableSignature()
            throws Exception {
        when(currentUsers.papelAcesso(COLLABORATOR_ID.toString()))
                .thenReturn(PapelAcesso.BETA);
        when(currentUsers.allowedObraIds(COLLABORATOR_ID.toString()))
                .thenReturn(Optional.of(Set.of(OBRA_Z, OBRA_A)));
        OfflineGrantService service = service(43_200);

        OfflineGrant grant = service.issue(COLLABORATOR_ID);

        assertThat(grant.keyId()).isEqualTo("test-key-2030-01");
        assertThat(grant.payload()).matches("[A-Za-z0-9_-]+");
        assertThat(grant.signature()).matches("[A-Za-z0-9_-]+");
        assertThat(grant.publicKeySpki()).matches("[A-Za-z0-9_-]+");
        JsonNode claims = decodeClaims(grant.payload());
        assertThat(claims.fieldNames()).toIterable().containsExactly(
                "versao",
                "colaboradorId",
                "nome",
                "papelAcesso",
                "escopoGlobal",
                "obraIds",
                "emitidoEm",
                "expiraEm"
        );
        assertThat(claims.path("versao").asInt()).isEqualTo(1);
        assertThat(claims.path("colaboradorId").asText())
                .isEqualTo(COLLABORATOR_ID.toString());
        assertThat(claims.path("nome").asText()).isEqualTo("Pessoa Sintética");
        assertThat(claims.path("papelAcesso").asText()).isEqualTo("BETA");
        assertThat(claims.path("escopoGlobal").asBoolean()).isFalse();
        assertThat(claims.path("obraIds")).isEqualTo(json.valueToTree(
                List.of(OBRA_A, OBRA_Z)
        ));
        assertThat(claims.path("emitidoEm").asText())
                .isEqualTo("2030-01-02T03:04:05.123456Z");
        assertThat(claims.path("expiraEm").asText())
                .isEqualTo("2030-01-02T15:04:05.123456Z");
        assertThat(verifies(grant)).isTrue();
    }

    @Test
    void alfaGrantCarriesGlobalScopeWithoutEnumeratingWorksites() throws Exception {
        when(currentUsers.papelAcesso(COLLABORATOR_ID.toString()))
                .thenReturn(PapelAcesso.ALFA);
        when(currentUsers.allowedObraIds(COLLABORATOR_ID.toString()))
                .thenReturn(Optional.empty());

        OfflineGrant grant = service(86_400).issue(COLLABORATOR_ID);

        JsonNode claims = decodeClaims(grant.payload());
        assertThat(claims.path("papelAcesso").asText()).isEqualTo("ALFA");
        assertThat(claims.path("escopoGlobal").asBoolean()).isTrue();
        assertThat(claims.path("obraIds").isArray()).isTrue();
        assertThat(claims.path("obraIds")).isEmpty();
    }

    @Test
    void failsClosedForUnknownRoleOrInconsistentScope() {
        when(currentUsers.papelAcesso(COLLABORATOR_ID.toString()))
                .thenReturn(null);

        assertThatThrownBy(() -> service(3_600).issue(COLLABORATOR_ID))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN)
                );

        when(currentUsers.papelAcesso(COLLABORATOR_ID.toString()))
                .thenReturn(PapelAcesso.BETA);
        when(currentUsers.allowedObraIds(COLLABORATOR_ID.toString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(3_600).issue(COLLABORATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Escopo");
    }

    @Test
    void rejectsTtlOutsideTheOneDaySecurityBoundary() {
        assertThatThrownBy(() -> service(86_401))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("86400");
        assertThatThrownBy(() -> service(0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOversizedScopeConfigurationAndNeverTruncatesAUserScope() {
        OfflineGrantProperties invalid = new OfflineGrantProperties();
        invalid.setMaxWorksites(801);
        assertThatThrownBy(() -> service(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("800");

        when(currentUsers.papelAcesso(COLLABORATOR_ID.toString()))
                .thenReturn(PapelAcesso.BETA);
        when(currentUsers.allowedObraIds(COLLABORATOR_ID.toString()))
                .thenReturn(Optional.of(Set.of(
                        OBRA_A,
                        OBRA_Z,
                        "20000000-0000-0000-0000-000000000005"
                )));
        OfflineGrantProperties bounded = new OfflineGrantProperties();
        bounded.setMaxWorksites(2);

        assertThatThrownBy(() -> service(bounded).issue(COLLABORATOR_ID))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.getReason())
                                    .contains("reconecte");
                        }
                );
    }

    private OfflineGrantService service(long ttlSeconds) {
        OfflineGrantProperties properties = new OfflineGrantProperties();
        properties.setTtlSeconds(ttlSeconds);
        return service(properties);
    }

    private OfflineGrantService service(OfflineGrantProperties properties) {
        OfflineGrantSigningKey key = new OfflineGrantSigningKey(
                "test-key-2030-01",
                keyPair.getPrivate(),
                keyPair.getPublic()
        );
        return new OfflineGrantService(
                currentUsers,
                subjects,
                key,
                properties
        );
    }

    private JsonNode decodeClaims(String payload) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(payload);
        return json.readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    private boolean verifies(OfflineGrant grant) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(Base64.getUrlDecoder().decode(grant.payload()));
        return verifier.verify(Base64.getUrlDecoder().decode(grant.signature()));
    }
}
