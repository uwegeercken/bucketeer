package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3PrefixAnalyzerTest {

    private static final S3Object DUMMY =
            new S3Object("obj", "bucket", 1L, Instant.EPOCH, "etag");

    private S3StoragePort s3StoragePort;
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));
    private S3PrefixAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        s3StoragePort = mock(S3StoragePort.class);
        analyzer = new S3PrefixAnalyzer(s3StoragePort, now::get);
    }

    @Test
    @DisplayName("sampling draws no more prefixes than the first level page holds")
    void sampleCountIsCappedByPrefixCount() {
        stubObjects(3, false);

        PrefixProfile profile = analyzer.profileFor("server", "bucket", "root/",
                prefixes(3), 8, 4);

        assertThat(profile.sampleCount()).isEqualTo(3);
        assertThat(profile.levelPrefixes()).isEqualTo(3);
        assertThat(profile.medianObjects()).isEqualTo(3);
        verify(s3StoragePort, times(3))
                .listObjects(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("sampling honours the configured count when more prefixes exist")
    void sampleCountIsHonoured() {
        stubObjects(5, false);

        PrefixProfile profile = analyzer.profileFor("server", "bucket", "root/",
                prefixes(20), 5, 4);

        assertThat(profile.sampleCount()).isEqualTo(5);
        assertThat(profile.levelPrefixes()).isEqualTo(20);
        verify(s3StoragePort, times(5))
                .listObjects(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("a sample count below 1 still takes a single sample")
    void sampleCountBelowOneIsClamped() {
        stubObjects(3, false);

        PrefixProfile profile = analyzer.profileFor("server", "bucket", "root/",
                prefixes(6), 0, 4);

        assertThat(profile.sampleCount()).isOne();
    }

    @Test
    @DisplayName("a truncated sample page counts as page + 1 instead of its visible size")
    void truncatedPageUsesLowerBound() {
        stubObjects(2, true);

        PrefixProfile profile = analyzer.profileFor("server", "bucket", "root/",
                prefixes(4), 4, 4);

        assertThat(profile.medianObjects()).isEqualTo(BucketeerService.LISTING_PAGE + 1);
        assertThat(profile.samples()).allMatch(PrefixProfile.Sample::truncated);
    }

    @Test
    @DisplayName("the median spans the sampled prefixes using the lower middle value")
    void medianSpansAllSamples() {
        when(s3StoragePort.listObjects(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    String prefix = invocation.getArgument(2);
                    int objects = prefix.equals("p002/") || prefix.equals("p003/") ? 900 : 3;
                    return new ObjectListing(Collections.nCopies(objects, DUMMY), null, false);
                });

        PrefixProfile profile = analyzer.profileFor("server", "bucket", "root/",
                prefixes(4), 4, 4);

        assertThat(profile.sampleCount()).isEqualTo(4);
        assertThat(profile.medianObjects()).isEqualTo(3);
        assertThat(profile.minObjects()).isEqualTo(3);
        assertThat(profile.maxObjects()).isEqualTo(900);
    }

    @Test
    @DisplayName("a fresh profile is served from the cache without further listings")
    void freshProfileIsCached() {
        stubObjects(3, false);

        analyzer.profileFor("server", "bucket", "root/", prefixes(6), 4, 4);
        analyzer.profileFor("server", "bucket", "root/", prefixes(6), 4, 4);

        verify(s3StoragePort, times(4))
                .listObjects(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("different scopes keep their own cache entries")
    void scopesAreCachedSeparately() {
        stubObjects(3, false);

        analyzer.profileFor("server", "bucket", "root/", prefixes(4), 4, 4);
        analyzer.profileFor("server", "bucket", "other/", prefixes(4), 4, 4);

        verify(s3StoragePort, times(8))
                .listObjects(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("a profile is sampled again once its TTL has passed")
    void profileExpiresAfterTtl() {
        stubObjects(3, false);
        analyzer.profileFor("server", "bucket", "root/", prefixes(6), 4, 4);

        now.set(now.get().plus(S3PrefixAnalyzer.TTL).plusSeconds(1));
        analyzer.profileFor("server", "bucket", "root/", prefixes(6), 4, 4);

        verify(s3StoragePort, times(8))
                .listObjects(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("raising the configured sample count re-probes the scope")
    void higherSampleCountTriggersReprobe() {
        stubObjects(3, false);
        analyzer.profileFor("server", "bucket", "root/", prefixes(10), 2, 4);

        PrefixProfile richer = analyzer.profileFor("server", "bucket", "root/",
                prefixes(10), 5, 4);

        assertThat(richer.sampleCount()).isEqualTo(5);
        verify(s3StoragePort, times(7))
                .listObjects(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("a failing sample listing is reported to the caller")
    void samplingFailurePropagates() {
        when(s3StoragePort.listObjects(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenThrow(new RuntimeException("s3 down"));

        assertThatThrownBy(() -> analyzer.profileFor("server", "bucket", "root/",
                prefixes(4), 4, 4))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("s3 down");
    }

    private static List<String> prefixes(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add("p%03d/".formatted(i));
        }
        return list;
    }

    private void stubObjects(int objects, boolean truncated) {
        when(s3StoragePort.listObjects(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenAnswer(invocation -> new ObjectListing(
                        Collections.nCopies(objects, DUMMY), null, truncated));
    }
}
