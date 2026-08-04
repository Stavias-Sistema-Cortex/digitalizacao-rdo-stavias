package com.projeto.cortex.auth;

import com.projeto.cortex.auth.otp.AuthRateLimitStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Disjuntor da porta que o campo realmente usa.
 *
 * <p>A passkey tinha o seu disjuntor e o desafio por e-mail tinha o dele. O
 * login direto por CPF, que é por onde entra quem trabalha na obra, não tinha
 * nenhum — e é justamente ele quem aceita a credencial mais adivinhável do
 * conjunto: CPF não é segredo, circula em nota fiscal e ficha de entrega. Sem
 * freio, testar CPFs em sequência até achar um cadastrado era só questão de
 * tempo de máquina.
 *
 * <p>Reaproveita o armazenamento de baldes que já existe e já está de pé em
 * produção ({@code PostgresqlRateLimitBucketRepository}), então o freio é
 * compartilhado entre instâncias e sobrevive a reinício — um contador em
 * memória seria zerado pelo próprio ataque, que costuma durar mais que a vida
 * de um contêiner no plano free.
 *
 * <p>São dois baldes, e a diferença entre eles importa. O <b>por origem</b> é o
 * que separa uma pessoa de um robô, mas só vale o que valer o IP: por trás de
 * proxy sem {@code cortex.auth.trusted-proxy-cidrs} configurado todo mundo
 * chega com o mesmo endereço, e esse balde vira um segundo balde global. O
 * <b>global</b> é o que continua valendo nessa situação, e por isso ele existe
 * separado em vez de ser só uma folga do primeiro: é o piso que segura mesmo
 * quando a origem não é distinguível.
 *
 * <p>Não há balde por CPF de propósito. Ele pareceria a defesa mais precisa e
 * seria uma porta nova: quem soubesse o CPF de alguém trancaria essa pessoa
 * para fora gastando o balde dela de fora, sem nunca acertar o login. Trocar
 * invasão por bloqueio de quem é de casa não é um câmbio aceitável.
 */
@Service
public class AuthLoginRateLimiter {

    private static final String BUCKET_DOMAIN =
            "cortex.auth.login-rate-limit.v1";

    private final AuthRateLimitStore buckets;
    private final AuthLoginRateLimitPolicy policy;

    @Autowired
    public AuthLoginRateLimiter(
            AuthRateLimitStore buckets,
            @Value("${cortex.auth.login.rate-limit.max-requests:15}")
            int maxRequests,
            @Value("${cortex.auth.login.rate-limit.global-max-requests:400}")
            int globalMaxRequests,
            @Value("${cortex.auth.login.rate-limit.window-seconds:900}")
            int windowSeconds
    ) {
        this(buckets, new AuthLoginRateLimitPolicy(
                maxRequests,
                globalMaxRequests,
                windowSeconds
        ));
    }

    public AuthLoginRateLimiter(
            AuthRateLimitStore buckets,
            AuthLoginRateLimitPolicy policy
    ) {
        this.buckets = buckets;
        this.policy = policy;
    }

    /**
     * Consulta o global antes de gastar o da origem para que uma enxurrada não
     * consuma o balde de quem chega depois: quando o piso global já estourou,
     * a recusa é imediata e nenhum balde por origem é queimado à toa.
     */
    public boolean allow(String clientIp) {
        String globalBucket = bucketKey("global", "direct-cpf");
        if (!buckets.hasCapacity(
                globalBucket,
                policy.globalMaxRequests(),
                policy.windowSeconds()
        )) {
            return false;
        }

        String source = clientIp == null || clientIp.isBlank()
                ? "invalid"
                : clientIp.strip();
        if (!buckets.consume(
                List.of(bucketKey("source", source)),
                policy.maxRequests(),
                policy.windowSeconds()
        )) {
            return false;
        }

        return buckets.consume(
                List.of(globalBucket),
                policy.globalMaxRequests(),
                policy.windowSeconds()
        );
    }

    private String bucketKey(String scope, String material) {
        return sha256(BUCKET_DOMAIN + "\0" + scope + "\0" + material);
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 indisponível.",
                    exception
            );
        }
    }
}
