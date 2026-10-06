package io.github.uwegeercken.bucketeer.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrefixProfileTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private static PrefixProfile profile(int levelPrefixes, int objects) {
        return PrefixProfile.of(levelPrefixes,
                List.of(new PrefixProfile.Sample("p/", objects, false, 1)), NOW);
    }

    @Test
    @DisplayName("a scope of tiny prefixes stays sequential even with eight workers")
    void tinyPrefixesStaySequential() {
        assertThat(profile(1000, 3).prefersParallel(8)).isFalse();
    }

    @Test
    @DisplayName("the break-even point sits at page / workers objects per prefix")
    void breakEvenFollowsPageOverWorkers() {
        // 8 workers -> splitting pays off above 1000/8 = 125 objects per prefix
        assertThat(profile(1000, 125).prefersParallel(8))
                .as("exactly at the threshold the flat stream still wins")
                .isFalse();
        assertThat(profile(1000, 126).prefersParallel(8)).isTrue();

        // 32 workers lower the threshold to 1000/32 = 31
        assertThat(profile(1000, 75).prefersParallel(8)).isFalse();
        assertThat(profile(1000, 75).prefersParallel(32)).isTrue();
    }

    @Test
    @DisplayName("prefixes that need several pages prefer the parallel split")
    void multiPagePrefixesPreferParallel() {
        assertThat(profile(1000, 1000).prefersParallel(8)).isTrue();
        assertThat(profile(1000, 50_000).prefersParallel(8)).isTrue();
    }

    @Test
    @DisplayName("a single top-level prefix can never be split")
    void singlePrefixStaysSequential() {
        assertThat(profile(1, 100_000).prefersParallel(8)).isFalse();
    }

    @Test
    @DisplayName("a worker count below 2 stays sequential")
    void parallelismBelowTwoStaysSequential() {
        assertThat(profile(1000, 5000).prefersParallel(1)).isFalse();
        assertThat(profile(1000, 5000).prefersParallel(0)).isFalse();
    }

    @Test
    @DisplayName("an empty sample set stays sequential")
    void emptySamplesStaySequential() {
        PrefixProfile empty = PrefixProfile.of(1000, List.of(), NOW);

        assertThat(empty.sampleCount()).isZero();
        assertThat(empty.prefersParallel(8)).isFalse();
    }

    @Test
    @DisplayName("the median takes the lower middle value and errs towards sequential")
    void medianTakesLowerMiddleValue() {
        PrefixProfile mixed = PrefixProfile.of(1000, List.of(
                new PrefixProfile.Sample("a/", 3, false, 1),
                new PrefixProfile.Sample("b/", 3, false, 1),
                new PrefixProfile.Sample("c/", 900, false, 1),
                new PrefixProfile.Sample("d/", 900, false, 1)), NOW);

        assertThat(mixed.sampleCount()).isEqualTo(4);
        assertThat(mixed.medianObjects()).isEqualTo(3);
        assertThat(mixed.minObjects()).isEqualTo(3);
        assertThat(mixed.maxObjects()).isEqualTo(900);
        assertThat(mixed.prefersParallel(8)).isFalse();
    }

    @Test
    @DisplayName("a truncated sample counts as page + 1, which already proves multi-page prefixes")
    void truncatedSampleUsesLowerBound() {
        PrefixProfile truncated = PrefixProfile.of(1000, List.of(
                new PrefixProfile.Sample("a/", BucketeerService.LISTING_PAGE + 1, true, 1)), NOW);

        assertThat(truncated.medianObjects()).isEqualTo(BucketeerService.LISTING_PAGE + 1);
        assertThat(truncated.prefersParallel(8)).isTrue();
    }

    @Test
    @DisplayName("a handful of prefixes prefers parallel only when the workers cover its round trips")
    void fewPrefixesNeedEnoughWorkers() {
        // 2 prefixes + 1 level page = 3 round trips; 4 workers make that cheaper than
        // the single flat request, 2 workers do not
        assertThat(profile(2, 3).prefersParallel(4)).isTrue();
        assertThat(profile(2, 3).prefersParallel(2)).isFalse();
    }

    @Test
    @DisplayName("very large scopes decide without overflowing")
    void largeScopesDoNotOverflow() {
        assertThat(profile(10_000_000, 50_000).prefersParallel(8)).isTrue();
        assertThat(profile(10_000_000, 3).prefersParallel(8)).isFalse();
    }
}
