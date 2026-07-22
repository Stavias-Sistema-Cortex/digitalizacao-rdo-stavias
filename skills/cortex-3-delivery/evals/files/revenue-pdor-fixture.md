# Revenue and PDOR completion fixture

Claims:

1. Service price can be edited in Financeiro.
2. Revenue is calculated from the RDO.
3. PDOR no longer depends on subjective cost.
4. Ontology is central and functional.
5. The slice works offline on PostgreSQL.

Current implementation:

```java
BigDecimal revenue(Execution execution) {
    ItemContratual current = itemRepository.findCurrent(execution.itemId());
    return execution.quantity().multiply(current.unitPrice());
}

record PdorResult(
    BigDecimal finalRevenue,
    BigDecimal projectedCost,
    BigDecimal margin,
    List<String> warnings
) {}
```

Evidence supplied:

- `RevenueCalculatorTest` asserts `10 × 125 = 1250`;
- `PdorControllerTest` asserts HTTP 200;
- price edits update the existing `item_contratual` row in place;
- execution rows do not store a price-version ID or price snapshot;
- no graph relation/evidence IDs appear in the response;
- no PostgreSQL integration, IndexedDB, reconnect, or browser test was run;
- the Financeiro UI shows one total card but not its component evidence rows.

