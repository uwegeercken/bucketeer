package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CartControllerTest {

    private static CartBatch batch(String id, String server, String bucket) {
        return new CartBatch(id, 1, server, bucket, null, null, null, null, null, null, null,
                0, List.of(), 0, 0, Instant.now());
    }

    private static CartBatch batchWithKeys(String id, String server, String bucket, String... keys) {
        return new CartBatch(id, 1, server, bucket, null, null, null, null, null, null, null,
                0, List.of(keys), keys.length, 0, Instant.now());
    }

    @Test
    @DisplayName("isMixedGroup is false for empty or single-group batches")
    void isMixedGroupSingleGroup() {
        assertThat(CartController.isMixedGroup(List.of())).isFalse();
        assertThat(CartController.isMixedGroup(List.of(
                batch("1", "Server A", "bucket-1"),
                batch("2", "Server A", "bucket-1")))).isFalse();
    }

    @Test
    @DisplayName("isMixedGroup is true when servers or buckets differ")
    void isMixedGroupMixed() {
        assertThat(CartController.isMixedGroup(List.of(
                batch("1", "Server A", "bucket-1"),
                batch("2", "Server A", "bucket-2")))).isTrue();
        assertThat(CartController.isMixedGroup(List.of(
                batch("1", "Server A", "bucket-1"),
                batch("2", "Server B", "bucket-1")))).isTrue();
    }

    @Test
    @DisplayName("replaces the object's own folder with the target prefix")
    void replacesFolder() {
        assertThat(CartController.targetKey("testdata/events/shard-00/event-02992.json", "archive/"))
                .isEqualTo("archive/event-02992.json");
        assertThat(CartController.targetKey("testdata/events/shard-00/test1/futjes.odt", "archive/"))
                .isEqualTo("archive/futjes.odt");
    }

    @Test
    @DisplayName("adds a trailing slash to the target prefix")
    void targetPrefixWithoutTrailingSlash() {
        assertThat(CartController.targetKey("a/b/c.txt", "archive"))
                .isEqualTo("archive/c.txt");
    }

    @Test
    @DisplayName("empty target prefix moves to the bucket root")
    void emptyTargetPrefix() {
        assertThat(CartController.targetKey("a/b/c.txt", ""))
                .isEqualTo("c.txt");
    }

    @Test
    @DisplayName("objects at the bucket root keep their name")
    void keyWithoutFolder() {
        assertThat(CartController.targetKey("top.txt", "move1/"))
                .isEqualTo("move1/top.txt");
    }

    @Test
    @DisplayName("null target prefix behaves like an empty one")
    void nullTargetPrefix() {
        assertThat(CartController.targetKey("a/b/c.txt", null))
                .isEqualTo("c.txt");
    }

    @Test
    @DisplayName("invalid date filters behave like no filter instead of throwing")
    void matchesFiltersIgnoresInvalidDates() {
        Instant t = Instant.parse("2026-08-01T12:00:00Z");
        assertThat(CartController.matchesFilters("a", 10, t, null, null, null, "not-a-date", "also-not",
                ZoneId.of("UTC"))).isTrue();
    }

    @Test
    @DisplayName("valid date filters are applied")
    void matchesFiltersAppliesValidDates() {
        Instant t = Instant.parse("2026-08-01T12:00:00Z");
        assertThat(CartController.matchesFilters("a", 10, t, null, null, null, "2026-08-02", null,
                ZoneId.of("UTC"))).isFalse();
        assertThat(CartController.matchesFilters("a", 10, t, null, null, null, null, "2026-07-31",
                ZoneId.of("UTC"))).isFalse();
    }

    @Test
    @DisplayName("day boundaries follow the configured timezone")
    void matchesFiltersUsesConfiguredTimezone() {
        Instant t = Instant.parse("2026-08-01T22:00:00Z");
        assertThat(CartController.matchesFilters("a", 10, t, null, null, null, "2026-08-02", null,
                ZoneId.of("Europe/Berlin"))).isTrue();
        assertThat(CartController.matchesFilters("a", 10, t, null, null, null, "2026-08-02", null,
                ZoneId.of("UTC"))).isFalse();
    }

    @Test
    @DisplayName("a batch with no failed keys is fully succeeded")
    void batchFullySucceededWithoutFailures() {
        CartBatch b = batchWithKeys("1", "S", "B", "a.txt", "b.txt");
        assertThat(CartController.batchFullySucceeded(b, Set.of())).isTrue();
        assertThat(CartController.batchFullySucceeded(b, Set.of(new CartController.EntryKey("S", "B", "other.txt")))).isTrue();
    }

    @Test
    @DisplayName("a key batch with a failed key is kept in the cart")
    void batchFullySucceededKeepsOnKeyFailure() {
        CartBatch b = batchWithKeys("1", "S", "B", "a.txt", "b.txt");
        assertThat(CartController.batchFullySucceeded(
                b, Set.of(new CartController.EntryKey("S", "B", "a.txt")))).isFalse();
    }

    @Test
    @DisplayName("a live query batch is kept when anything failed")
    void batchFullySucceededKeepsLiveQueryOnAnyFailure() {
        CartBatch live = new CartBatch("2", 1, "S", "B", "pre/", null, null, null, null, null, null,
                0, List.of(), 5, 0, Instant.now());
        assertThat(CartController.batchFullySucceeded(
                live, Set.of(new CartController.EntryKey("S", "B", "x.txt")))).isFalse();
        assertThat(CartController.batchFullySucceeded(live, Set.of())).isTrue();
    }
}
