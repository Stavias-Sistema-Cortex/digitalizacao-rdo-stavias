# Offline RDO fixture

Implemented behavior:

```ts
function handleCreate() {
  const draft = createEmptyRdo();
  draft.obraId = "obra-demo";
  draft.numeroRdo = "RDO-001";
  draft.maoObra = [
    { colaboradorId: null, nomeColaborador: "João", cargo: "Apontador" },
    { colaboradorId: null, nomeColaborador: "Carlos", cargo: "Encarregado" }
  ];
  saveRdo(draft);
  navigate(`/rdos/${draft.id}`);
}

async function syncNow() {
  const pending = memoryQueue;
  await api.push(pending);
  setStatus("SYNCED");
}
```

Evidence supplied:

- one component test checks that clicking `Novo RDO` navigates to `/rdos/novo`;
- no IndexedDB transaction test;
- no previous RDO query or provenance;
- no reload/offline/reconnect test;
- sync runs only when the user presses `Sincronizar`;
- worksite and workers above appear in production source.

