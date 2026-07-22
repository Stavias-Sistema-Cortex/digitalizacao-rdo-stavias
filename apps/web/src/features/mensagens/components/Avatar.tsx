import type { ConversaLocalRecord } from "../../../lib/db/db.types";
import {
  sementeDaConversa,
  silhuetaDaConversa,
  tintaDoAvatar,
  type AvatarSilhueta,
} from "../mensagensAvatar";

/**
 * Retrato-padrão da aba: busto recortado pelo disco, como um crachá cortado na
 * altura do peito. O tamanho vem do contexto (`--avatar-size`), então o mesmo
 * componente serve da lista de 44px ao bloco de identidade de 84px.
 */
function Avatar({
  seed,
  silhueta,
  pendente,
}: {
  seed: string;
  silhueta: AvatarSilhueta;
  pendente?: boolean;
}) {
  return (
    <span
      className={`mensagens-avatar mensagens-avatar--t${tintaDoAvatar(seed)}`}
      aria-hidden="true"
    >
      <span className="mensagens-avatar-disco">
        <Silhueta tipo={silhueta} />
      </span>
      {pendente ? <i className="mensagens-avatar-dot" /> : null}
    </span>
  );
}

export function ConversationAvatar({
  conversation,
  currentUserId,
  pendente,
}: {
  conversation: ConversaLocalRecord;
  currentUserId: string;
  pendente?: boolean;
}) {
  return (
    <Avatar
      seed={sementeDaConversa(conversation, currentUserId)}
      silhueta={silhuetaDaConversa(conversation.tipo)}
      pendente={pendente}
    />
  );
}

export function PersonAvatar({ colaboradorId }: { colaboradorId: string }) {
  return <Avatar seed={colaboradorId} silhueta="pessoa" />;
}

function Silhueta({ tipo }: { tipo: AvatarSilhueta }) {
  if (tipo === "dupla") return <SilhuetaDupla />;
  if (tipo === "obra") return <SilhuetaObra />;
  return <SilhuetaPessoa />;
}

/*
 * Os bustos passam de propósito das bordas do viewBox: quem recorta é o disco.
 * Encolhê-los para caber deixaria a figura boiando no meio do círculo.
 */

/* O vão entre cabeça e ombros é de propósito: sem ele a figura vira um pino. */
function SilhuetaPessoa() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <circle cx="12" cy="8.7" r="5" />
      <ellipse cx="12" cy="22.4" rx="10.4" ry="8.2" />
    </svg>
  );
}

/* A figura de trás sai em meio-tom: com a mesma tinta as duas viram um borrão. */
function SilhuetaDupla() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <g opacity="0.52">
        <circle cx="6.8" cy="8.6" r="3.8" />
        <ellipse cx="5.8" cy="21.6" rx="8" ry="7.4" />
      </g>
      <circle cx="14.8" cy="9" r="4.6" />
      <ellipse cx="15" cy="22.8" rx="9.4" ry="8.4" />
    </svg>
  );
}

/*
 * Capacete. As proporções são o desenho todo: a copa tem de ser bem mais larga
 * que o rosto e a aba tem de sobrar pouco além da copa. Aba fina e larga demais
 * vira sombrero; copa larga demais, sem sobra de aba, vira abajur.
 */
function SilhuetaObra() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M5.6 8.8a6.4 5.6 0 0 1 12.8 0Z" />
      <ellipse cx="12" cy="9.2" rx="8.4" ry="1.7" />
      <path d="M8.2 10.1h7.6v0.7a3.8 3.8 0 0 1-7.6 0Z" />
      <ellipse cx="12" cy="23.6" rx="10.4" ry="8.4" />
    </svg>
  );
}
