import "./institutional.css";

export interface TraceReferenceProps {
  eventId: string;
  entityId: string;
  href?: string;
  className?: string;
}

export function TraceReference({
  eventId,
  entityId,
  href,
  className,
}: TraceReferenceProps) {
  const classNames = [
    "trace-reference",
    className,
  ]
    .filter(Boolean)
    .join(" ");
  const traceHref =
    href ??
    `/home?tab=memory&event=${encodeURIComponent(eventId)}`;

  return (
    <a
      aria-label={`Ver evento ${eventId} da entidade ${entityId} na Memória`}
      className={classNames}
      data-entity-id={entityId}
      data-event-id={eventId}
      href={traceHref}
    >
      <span>Ver na Memória</span>
      <span aria-hidden="true" className="trace-reference__id">
        {eventId}
      </span>
    </a>
  );
}
