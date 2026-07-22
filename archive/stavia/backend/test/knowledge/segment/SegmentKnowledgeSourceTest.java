package com.projeto.cortex.intelligence.stavia.knowledge.segment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentKnowledgeSourceTest {

    @Test
    void shouldParseQuestionRangeAndMatchOverlappingControl() {
        SegmentKnowledgeSource.KilometreRange requested =
                SegmentKnowledgeSource.KilometreRange
                        .from("O que aconteceu entre o km 10 e o km 12?")
                        .orElseThrow();

        SegmentKnowledgeSource.KilometreRange control =
                SegmentKnowledgeSource.KilometreRange
                        .from("11+500", "12+300")
                        .orElseThrow();

        assertTrue(control.overlaps(requested));
    }

    @Test
    void shouldRejectQuestionWithoutKilometreReference() {
        assertTrue(
                SegmentKnowledgeSource.KilometreRange
                        .from("Quais RDOs existem nesta obra?")
                        .isEmpty()
        );

        SegmentKnowledgeSource.KilometreRange first =
                new SegmentKnowledgeSource.KilometreRange(
                        BigDecimal.TEN,
                        BigDecimal.valueOf(12)
                );
        SegmentKnowledgeSource.KilometreRange second =
                new SegmentKnowledgeSource.KilometreRange(
                        BigDecimal.valueOf(13),
                        BigDecimal.valueOf(15)
                );

        assertFalse(first.overlaps(second));
    }
}
