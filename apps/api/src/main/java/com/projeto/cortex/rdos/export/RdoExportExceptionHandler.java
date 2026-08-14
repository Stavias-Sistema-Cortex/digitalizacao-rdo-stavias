package com.projeto.cortex.rdos.export;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Faz a recusa da exportação chegar dita, e não codificada.
 *
 * <p>A exportação recusa com 422 e escreve o motivo em português, com cuidado:
 * "Há mão de obra sem cargo ou nome; nenhum item foi truncado", "O template RDO
 * v1 comporta N serviços, mas o RDO possui M", "vínculo de equipamento não
 * reconhecido: X. Nenhuma categoria foi inventada". São catorze recusas
 * distintas, cada uma dizendo o que corrigir e afirmando que nada foi inventado
 * nem cortado.
 *
 * <p>Nenhuma chegava à tela. O padrão do Spring Boot é
 * {@code server.error.include-message: never}, que apaga o campo antes de a
 * resposta sair do processo: o corpo do 422 ia com
 * {@code {"status":422,"error":"Unprocessable Entity"}} e nada mais. Do outro
 * lado, o aplicativo procura {@code message}, depois {@code detail}, depois
 * {@code error}, e caía no terceiro — quem tentava exportar lia "Unprocessable
 * Entity", uma frase que não é português, não diz o que corrigir e não
 * distingue um RDO de outro.
 *
 * <p>A correção é aqui, e não em configuração global, e isso foi aprendido
 * errando: ligar {@code spring.mvc.problemdetails} registra um advice de
 * precedência máxima que captura <em>todo</em> {@code ResponseStatusException}
 * da aplicação — e atropelou o {@code PdorExceptionHandler}, que já tem
 * contrato próprio com o campo {@code mensagem}. Um interruptor global para
 * resolver o problema de um endpoint muda a resposta de todos os outros.
 *
 * <p>Este advice é limitado por {@code assignableTypes} ao controlador da
 * exportação, exatamente como o do PDOR. O que muda fora daqui: nada.
 */
@RestControllerAdvice(assignableTypes = RdoExportController.class)
public class RdoExportExceptionHandler {

    /**
     * O corpo que a tela sabe ler.
     *
     * <p>O nome do campo é {@code message} porque é o primeiro que
     * {@code responseErrorMessage} procura no aplicativo. {@code error} vem
     * junto, com a frase do status, para quem inspeciona a resposta crua.
     */
    public record RdoExportErrorResponse(
            int status,
            String error,
            String message
    ) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RdoExportErrorResponse> handleRefusal(
            ResponseStatusException exception
    ) {
        HttpStatus status = HttpStatus.valueOf(
                exception.getStatusCode().value()
        );
        String reason = exception.getReason();

        return ResponseEntity
                .status(status)
                .body(new RdoExportErrorResponse(
                        status.value(),
                        status.getReasonPhrase(),
                        reason == null || reason.isBlank()
                                ? status.getReasonPhrase()
                                : reason
                ));
    }
}
