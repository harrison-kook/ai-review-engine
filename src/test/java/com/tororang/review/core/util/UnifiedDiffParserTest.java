package com.tororang.review.core.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffParserTest {

    private final UnifiedDiffParser parser = new UnifiedDiffParser();

    @Test
    void extractsAddedLineNumbersFromNewFilePerspective() {
        String patch = """
                @@ -10,7 +10,8 @@ public class OrderService {
                     public class OrderService {
                     public void approve(String orderId) {
                -        pgClient.approve(orderId);
                +        if (alreadyApproved(orderId)) return;
                +        pgClient.approve(orderId);
                     }""";

        Set<Integer> addedLines = parser.parseAddedLines(patch);

        assertThat(addedLines).containsExactly(12, 13);
    }

    @Test
    void handlesMultipleHunks() {
        String patch = """
                @@ -1,2 +1,3 @@
                 line1
                +newline2
                 line2
                @@ -20,2 +21,2 @@
                -oldline20
                +newline21""";

        Set<Integer> addedLines = parser.parseAddedLines(patch);

        assertThat(addedLines).containsExactly(2, 21);
    }

    @Test
    void returnsEmptySetForBlankPatch() {
        assertThat(parser.parseAddedLines(null)).isEmpty();
        assertThat(parser.parseAddedLines("")).isEmpty();
    }

    @Test
    void ignoresNoNewlineAtEndOfFileMarker() {
        String patch = """
                @@ -1,1 +1,1 @@
                -old
                +new
                \\ No newline at end of file""";

        Set<Integer> addedLines = parser.parseAddedLines(patch);

        assertThat(addedLines).containsExactly(1);
    }
}