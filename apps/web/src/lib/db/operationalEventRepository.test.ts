import { describe, expect, it } from "vitest";

import * as eventRepository from "./operationalEventRepository";

type CanonicalBuilder = (
  input: Record<string, unknown>,
) => Record<string, unknown>;

const builders = eventRepository as typeof eventRepository & {
  buildLegacyOperationalEvent: typeof eventRepository.buildOperationalEvent;
  buildCanonicalOperationalEvent: CanonicalBuilder;
};

describe("operational event contract builders", () => {
  it("exposes the existing builder as explicit legacy compatibility", () => {
    expect(builders.buildLegacyOperationalEvent).toBeTypeOf("function");
    expect(builders.buildOperationalEvent).toBe(
      builders.buildLegacyOperationalEvent,
    );
  });

  it("requires and populates the complete canonical correlation contract", () => {
    expect(builders.buildCanonicalOperationalEvent).toBeTypeOf("function");

    const event = builders.buildCanonicalOperationalEvent({
      id: "event-1",
      clientMutationId: "mutation-1",
      deviceId: "device-1",
      correlationId: "correlation-1",
      causationId: null,
      type: "RDO_EDITADO",
      principalEntity: { tipo: "RDO", id: "rdo-1" },
      previousState: { status: "RASCUNHO" },
      newState: { status: "ENVIADO" },
      result: "PENDING",
      errorCategory: null,
      entityVersion: 4,
      occurredAt: "2026-07-17T12:00:00.000Z",
    });

    expect(event).toMatchObject({
      contractVersion: 13,
      clientMutationId: "mutation-1",
      deviceId: "device-1",
      correlationId: "correlation-1",
      causationId: null,
      principalEntityKey: "RDO:rdo-1",
      previousState: { status: "RASCUNHO" },
      newState: { status: "ENVIADO" },
      result: "PENDING",
      errorCategory: null,
      entityVersion: 4,
    });
  });
});
