package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.model.LevelListing;
import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.PrefixCount;
import io.github.uwegeercken.bucketeer.domain.model.PrefixScan;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import io.github.uwegeercken.bucketeer.infrastructure.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BucketeerServiceTest {

    private static final S3Properties PROPERTIES =
            new S3Properties("0.9.2", "2026-10-09",
                    new S3Properties.Query(4), new S3Properties.Scan(10, 3));

    private S3StoragePort s3StoragePort;
    private AppSettings appSettings;
    private BucketeerService service;
    private PrefixAnalyzer prefixAnalyzer;

    /** AppSettings stub that reports the given effective parallelism. */
    private static AppSettings appSettingsWith(int parallelism) {
        AppSettings settings = mock(AppSettings.class);
        when(settings.getQueryParallelism()).thenReturn(parallelism);
        when(settings.getQuerySampleSize()).thenReturn(AppSettings.DEFAULT_QUERY_SAMPLE_SIZE);
        return settings;
    }

    @BeforeEach
    void setUp() {
        s3StoragePort = mock(S3StoragePort.class);
        appSettings = appSettingsWith(4);
        prefixAnalyzer = mock(PrefixAnalyzer.class);
        // default: a scope that pays off in parallel (multi-page sub prefixes)
        when(prefixAnalyzer.profileFor(anyString(), anyString(), anyString(),
                anyList(), anyInt(), anyInt()))
                .thenReturn(PrefixProfile.of(2, List.of(
                        new PrefixProfile.Sample("a/", BucketeerService.LISTING_PAGE, false, 1),
                        new PrefixProfile.Sample("b/", BucketeerService.LISTING_PAGE, false, 1)),
                        Instant.now()));
        service = new BucketeerService(s3StoragePort, null, PROPERTIES, prefixAnalyzer, appSettings);
    }

    @Test
    @DisplayName("moveObject copies then deletes the source")
    void moveCopiesThenDeletes() {
        when(s3StoragePort.copyObject("server", "bucket", "a/old.txt", "bucket", "a/new.txt", false))
                .thenReturn(true);

        boolean moved = service.moveObject("server", "bucket", "a/old.txt", "a/new.txt");

        assertThat(moved).isTrue();
        verify(s3StoragePort).copyObject("server", "bucket", "a/old.txt", "bucket", "a/new.txt", false);
        verify(s3StoragePort).deleteObject("server", "bucket", "a/old.txt");
    }

    @Test
    @DisplayName("moveObject skips when the target exists and leaves the source untouched")
    void moveSkipsWhenTargetExists() {
        when(s3StoragePort.copyObject(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(false);

        boolean moved = service.moveObject("server", "bucket", "a/old.txt", "a/new.txt");

        assertThat(moved).isFalse();
        verify(s3StoragePort, never()).deleteObject(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("moveObject does not delete the source when the copy fails")
    void moveDoesNotDeleteOnCopyFailure() {
        when(s3StoragePort.copyObject(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenThrow(new RuntimeException("copy failed"));

        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "a/new.txt"))
                .hasMessageContaining("copy failed");
        verify(s3StoragePort, never()).deleteObject(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("moveObject reports a possible duplicate when the copy succeeded but the delete fails")
    void moveWarnsAboutDuplicateOnDeleteFailure() {
        when(s3StoragePort.copyObject(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(true);
        doThrow(new RuntimeException("Access Denied"))
                .when(s3StoragePort).deleteObject(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "a/new.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copy created at a/new.txt")
                .hasMessageContaining("deletion of a/old.txt failed")
                .hasMessageContaining("duplicate may exist");
        verify(s3StoragePort).copyObject("server", "bucket", "a/old.txt", "bucket", "a/new.txt", false);
        verify(s3StoragePort).deleteObject("server", "bucket", "a/old.txt");
    }

    @Test
    @DisplayName("moveObject rejects an empty target key")
    void moveRejectsEmptyTarget() {
        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verifyNoInteractions(s3StoragePort);
    }

    @Test
    @DisplayName("moveObject rejects a target equal to the source")
    void moveRejectsSameTarget() {
        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "a/old.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
        verifyNoInteractions(s3StoragePort);
    }

    @Test
    @DisplayName("deleteObject delegates to the storage port")
    void deleteDelegates() {
        service.deleteObject("server", "bucket", "a/file.txt");
        verify(s3StoragePort).deleteObject("server", "bucket", "a/file.txt");
    }

    @Test
    @DisplayName("getObjectTags delegates to the storage port")
    void getObjectTagsDelegates() {
        when(s3StoragePort.getObjectTags("server", "bucket", "a/file.txt"))
                .thenReturn(Map.of("env", "prod"));

        Map<String, String> tags = service.getObjectTags("server", "bucket", "a/file.txt");

        assertThat(tags).isEqualTo(Map.of("env", "prod"));
        verify(s3StoragePort).getObjectTags("server", "bucket", "a/file.txt");
    }

    @Test
    @DisplayName("scanPrefixes uses the configured default limit when no limit is given")
    void scanUsesDefaultLimit() {
        when(s3StoragePort.scanCommonPrefixes("server", "bucket", "", null, 10))
                .thenReturn(new PrefixScan(List.of("data/"), null, false));

        PrefixScan scan = service.scanPrefixes("server", "bucket", null, 0, null);

        assertThat(scan.prefixes()).containsExactly("data/");
        verify(s3StoragePort).scanCommonPrefixes("server", "bucket", "", null, 10);
    }

    @Test
    @DisplayName("scanPrefixes caps the requested limit to the configured maximum")
    void scanCapsRequestedLimit() {
        service.scanPrefixes("server", "bucket", "data/", 25, "tok");

        verify(s3StoragePort).scanCommonPrefixes("server", "bucket", "data/", "tok", 10);
    }

    @Test
    @DisplayName("scanPrefixes keeps a requested limit below the configured maximum")
    void scanKeepsLowerLimit() {
        service.scanPrefixes("server", "bucket", "data/2024/", 3, null);

        verify(s3StoragePort).scanCommonPrefixes("server", "bucket", "data/2024/", null, 3);
    }

    @Test
    @DisplayName("scanPrefixes rejects a prefix deeper than the configured max depth")
    void scanRejectsTooDeepPrefix() {
        assertThatThrownBy(() -> service.scanPrefixes("server", "bucket", "a/b/c/d/", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum of 3");
        verifyNoInteractions(s3StoragePort);
    }

    @Test
    @DisplayName("countPrefixes returns the sub-prefix count per prefix, signalled as n+ when capped")
    void countMapsCountsAndCaps() {
        when(s3StoragePort.countCommonPrefixes("server", "bucket", "data/2024/"))
                .thenReturn(new PrefixCount(12, false));
        when(s3StoragePort.countCommonPrefixes("server", "bucket", "data/2025/"))
                .thenReturn(new PrefixCount(1000, true));

        List<String> counts = service.countPrefixes("server", "bucket", List.of("data/2024/", "data/2025/"));

        assertThat(counts).containsExactly("12", "1000+");
    }

    @Test
    @DisplayName("countPrefixes returns an empty list without touching storage when no prefixes are given")
    void countEmptyInput() {
        assertThat(service.countPrefixes("server", "bucket", List.of())).isEmpty();
        verifyNoInteractions(s3StoragePort);
    }

    @Test
    @DisplayName("countPrefixes reports an empty entry when the count fails")
    void countReportsUnknownOnFailure() {
        when(s3StoragePort.countCommonPrefixes(eq("server"), eq("bucket"), anyString()))
                .thenThrow(new RuntimeException("boom"));

        List<String> counts = service.countPrefixes("server", "bucket", List.of("data/2024/"));

        assertThat(counts).containsExactly("");
    }

    @Test
    @DisplayName("fetchAllObjects lists sequentially when the scope has no common prefixes")
    void fetchFlatSequentially() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("data/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(obj("data/a")), List.of(), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), eq("data/"), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(obj("data/a"), obj("data/b")), null, false));

        List<S3Object> collected = new ArrayList<>();
        boolean limit = service.fetchAllObjects("server", "bucket", "data/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isFalse();
        assertThat(collected.stream().map(S3Object::key)).containsExactly("data/a", "data/b");
        verify(s3StoragePort).listObjectsWithLevel("server", "bucket", "data/", null, 1000);
    }

    @Test
    @DisplayName("fetchAllObjects harvests common prefixes in parallel when a scope has multiple prefixes")
    void fetchParallelAcrossPrefixes() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(
                        new LevelListing(List.of(obj("a/root.txt")), List.of("a/", "b/"), "tok", true),
                        new LevelListing(List.of(obj("b/direct.txt")), List.of("c/"), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), anyString(), any(), anyLong()))
                .thenAnswer(inv -> {
                    String prefix = inv.getArgument(2);
                    return switch (prefix) {
                        case "a/" -> new ObjectListing(List.of(obj("a/1.txt"), obj("a/2.txt")), null, false);
                        case "b/" -> new ObjectListing(List.of(obj("b/3.txt")), null, false);
                        case "c/" -> new ObjectListing(List.of(obj("c/4.txt")), null, false);
                        default -> new ObjectListing(List.of(), null, false);
                    };
                });

        List<S3Object> collected = new ArrayList<>();
        boolean limit = service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isFalse();
        assertThat(collected.stream().map(S3Object::key).sorted().toList())
                .containsExactly("a/1.txt", "a/2.txt", "a/root.txt", "b/3.txt", "b/direct.txt", "c/4.txt");
    }

    @Test
    @DisplayName("fetchAllObjects stops early when maxObjects is reached in parallel mode")
    void fetchParallelStopsAtLimit() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), anyString(), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(obj("a/1.txt"), obj("a/2.txt")), null, false));

        List<S3Object> collected = new ArrayList<>();
        boolean limit = service.fetchAllObjects("server", "bucket", "root/", 2,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isTrue();
        assertThat(collected).hasSize(2);
    }

    @Test
    @DisplayName("fetchAllObjects trims the crossing page to the exact limit in parallel mode")
    void fetchParallelTrimsToExactLimit() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), eq("a/"), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(obj("a/1.txt"), obj("a/2.txt")), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), eq("b/"), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(obj("b/1.txt"), obj("b/2.txt")), null, false));

        List<S3Object> collected = new ArrayList<>();
        boolean limit = service.fetchAllObjects("server", "bucket", "root/", 3,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isTrue();
        assertThat(collected).hasSize(3);
    }

    @Test
    @DisplayName("fetchAllObjects propagates a listing failure in parallel mode")
    void fetchParallelPropagatesFailure() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), anyString(), any(), anyLong()))
                .thenThrow(new RuntimeException("boom"));

        List<S3Object> collected = new ArrayList<>();
        assertThatThrownBy(() -> service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> collected.addAll(page.objects())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("fetchAllObjects stops immediately when the page callback aborts a sequential listing")
    void fetchSequentialAbortsWhenCallbackThrows() {
        BucketeerService seqService =
                new BucketeerService(s3StoragePort, null, PROPERTIES, prefixAnalyzer, appSettingsWith(1));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), eq("data/"), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(obj("data/a")), "tok1", true),
                        new ObjectListing(List.of(obj("data/b")), null, false));

        AtomicInteger pages = new AtomicInteger();
        assertThatThrownBy(() -> seqService.fetchAllObjects("server", "bucket", "data/", 0,
                page -> {
                    if (pages.incrementAndGet() > 1) {
                        throw new RuntimeException("cancel");
                    }
                }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cancel");

        // the loop must not run on after the callback aborts
        verify(s3StoragePort, times(2)).listObjects(eq("server"), eq("bucket"), eq("data/"), any(), anyLong());
    }

    @Test
    @DisplayName("fetchAllObjects tears the workers down cleanly when the page callback aborts a parallel listing")
    void fetchParallelAbortsWhenCallbackThrows() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), anyString(), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(obj("a/1.txt"), obj("a/2.txt")), null, false));

        assertThatThrownBy(() -> service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> { throw new RuntimeException("cancel"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cancel");
    }

    @Test
    @DisplayName("fetchAllObjects uses a sequential listing when parallelism is disabled")
    void fetchSequentialWhenParallelismDisabled() {
        BucketeerService seqService =
                new BucketeerService(s3StoragePort, null, PROPERTIES, prefixAnalyzer, appSettingsWith(0));
        when(s3StoragePort.listObjects("server", "bucket", "data/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("data/a")), null, false));

        List<S3Object> collected = new ArrayList<>();
        boolean limit = seqService.fetchAllObjects("server", "bucket", "data/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isFalse();
        assertThat(collected.stream().map(S3Object::key)).containsExactly("data/a");
        verify(s3StoragePort, never()).listObjectsWithLevel(anyString(), anyString(), anyString(), any(), anyLong());

        BucketeerService singleWorker =
                new BucketeerService(s3StoragePort, null, PROPERTIES, prefixAnalyzer, appSettingsWith(1));
        collected.clear();
        boolean oneLimit = singleWorker.fetchAllObjects("server", "bucket", "data/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(oneLimit).isFalse();
        assertThat(collected.stream().map(S3Object::key)).containsExactly("data/a");
        verify(s3StoragePort, times(2)).listObjects(anyString(), anyString(), anyString(), isNull(), anyLong());
        verify(s3StoragePort, never()).listObjectsWithLevel(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("fetchAllObjects prefers the runtime parallelism setting over the application.yml default")
    void fetchUsesRuntimeParallelismOverYml() {
        // PROPERTIES declares parallelism 4 (application.yml), the stored setting 1 must win
        BucketeerService seqService =
                new BucketeerService(s3StoragePort, null, PROPERTIES, prefixAnalyzer, appSettingsWith(1));
        when(s3StoragePort.listObjects("server", "bucket", "data/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("data/a")), null, false));

        List<S3Object> collected = new ArrayList<>();
        seqService.fetchAllObjects("server", "bucket", "data/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(collected).hasSize(1);
        verify(s3StoragePort, never()).listObjectsWithLevel(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("fetchAllObjects lists sequentially when the prefix analysis prefers it")
    void fetchSequentialWhenAnalysisPrefersIt() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects("server", "bucket", "root/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("root/1.txt")), "tok", true));
        when(s3StoragePort.listObjects("server", "bucket", "root/", "tok", 0))
                .thenReturn(new ObjectListing(List.of(obj("root/2.txt")), null, false));
        // tiny sub prefixes in a wide scope: one flat stream beats one request per prefix
        when(prefixAnalyzer.profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(PrefixProfile.of(1000, List.of(
                        new PrefixProfile.Sample("a/", 3, false, 1),
                        new PrefixProfile.Sample("b/", 3, false, 1)), Instant.now()));

        List<S3Object> collected = new ArrayList<>();
        boolean limit = service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isFalse();
        assertThat(collected.stream().map(S3Object::key).toList())
                .containsExactly("root/1.txt", "root/2.txt");
        // the flat stream follows the continuation token and never splits into prefixes
        verify(s3StoragePort).listObjects("server", "bucket", "root/", "tok", 0);
        verify(s3StoragePort, never())
                .listObjects(eq("server"), eq("bucket"), eq("a/"), any(), anyLong());
        // the analysis sees the first level page, the sample count and the worker count
        verify(prefixAnalyzer).profileFor("server", "bucket", "root/",
                List.of("a/", "b/"), AppSettings.DEFAULT_QUERY_SAMPLE_SIZE, 4);
    }

    @Test
    @DisplayName("fetchAllObjects skips the prefix analysis when parallelism is below 2")
    void fetchSkipsAnalysisWhenParallelismDisabled() {
        BucketeerService seqService =
                new BucketeerService(s3StoragePort, null, PROPERTIES, prefixAnalyzer, appSettingsWith(1));
        when(s3StoragePort.listObjects("server", "bucket", "data/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("data/a")), null, false));

        List<S3Object> collected = new ArrayList<>();
        seqService.fetchAllObjects("server", "bucket", "data/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(collected).hasSize(1);
        verify(prefixAnalyzer, never())
                .profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt());
        verify(s3StoragePort, never()).listObjectsWithLevel(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("fetchAllObjects falls back to a sequential listing when the analysis fails")
    void fetchSequentialWhenAnalysisFails() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects("server", "bucket", "root/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("root/1.txt")), null, false));
        when(prefixAnalyzer.profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("sampling down"));

        List<S3Object> collected = new ArrayList<>();
        boolean limit = service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isFalse();
        assertThat(collected.stream().map(S3Object::key)).containsExactly("root/1.txt");
        verify(s3StoragePort, never())
                .listObjects(eq("server"), eq("bucket"), eq("a/"), any(), anyLong());
    }

    @Test
    @DisplayName("fetchAllObjects captures a parallel report with analysis details")
    void reportCapturesParallelAnalysis() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(obj("a/root.txt")), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), anyString(), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(), null, false));
        // the default profile in setUp prefers parallel (multi-page sub prefixes)

        AtomicReference<ListingReport> reportRef = new AtomicReference<>();
        service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> { }, reportRef::set);

        ListingReport report = reportRef.get();
        assertThat(report).isNotNull();
        assertThat(report.serverName()).isEqualTo("server");
        assertThat(report.bucket()).isEqualTo("bucket");
        assertThat(report.prefix()).isEqualTo("root/");
        assertThat(report.workers()).isEqualTo(4);
        assertThat(report.decision()).isEqualTo(ListingReport.Decision.PARALLEL);
        assertThat(report.parallel()).isTrue();
        assertThat(report.medianObjects()).isEqualTo(BucketeerService.LISTING_PAGE);
        assertThat(report.sampleCount()).isEqualTo(2);
        assertThat(report.levelPrefixes()).isEqualTo(2);
        assertThat(report.analysisCached()).isFalse();
        assertThat(report.searchedAt()).isNotNull();
    }

    @Test
    @DisplayName("fetchAllObjects captures a sequential report when the analysis prefers it")
    void reportCapturesBalancedSequential() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects("server", "bucket", "root/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("root/1.txt")), null, false));
        // tiny sub prefixes in a wide scope: one flat stream wins
        when(prefixAnalyzer.profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(PrefixProfile.of(1000, List.of(
                        new PrefixProfile.Sample("a/", 3, false, 1),
                        new PrefixProfile.Sample("b/", 3, false, 1)), Instant.now()));

        AtomicReference<ListingReport> reportRef = new AtomicReference<>();
        service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> { }, reportRef::set);

        ListingReport report = reportRef.get();
        assertThat(report.decision()).isEqualTo(ListingReport.Decision.SEQUENTIAL_BALANCED);
        assertThat(report.parallel()).isFalse();
        assertThat(report.workers()).isEqualTo(4);
        assertThat(report.medianObjects()).isEqualTo(3);
        assertThat(report.hasAnalysis()).isTrue();
    }

    @Test
    @DisplayName("fetchAllObjects captures a no-split report for a single top-level prefix")
    void reportCapturesNoSplit() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("data/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(obj("data/a")), List.of("data/"), null, false));
        when(s3StoragePort.listObjects("server", "bucket", "data/", null, 0))
                .thenReturn(new ObjectListing(List.of(), null, false));

        AtomicReference<ListingReport> reportRef = new AtomicReference<>();
        service.fetchAllObjects("server", "bucket", "data/", 0,
                page -> { }, reportRef::set);

        ListingReport report = reportRef.get();
        assertThat(report.decision()).isEqualTo(ListingReport.Decision.SEQUENTIAL_NO_SPLIT);
        assertThat(report.workers()).isEqualTo(4);
        assertThat(report.hasAnalysis()).isFalse();
        verify(prefixAnalyzer, never())
                .profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("fetchAllObjects captures a parallelism-disabled report")
    void reportCapturesParallelismDisabled() {
        BucketeerService seqService =
                new BucketeerService(s3StoragePort, null, PROPERTIES, prefixAnalyzer, appSettingsWith(1));
        when(s3StoragePort.listObjects("server", "bucket", "data/", null, 0))
                .thenReturn(new ObjectListing(List.of(), null, false));

        AtomicReference<ListingReport> reportRef = new AtomicReference<>();
        seqService.fetchAllObjects("server", "bucket", "data/", 0,
                page -> { }, reportRef::set);

        ListingReport report = reportRef.get();
        assertThat(report.decision()).isEqualTo(ListingReport.Decision.SEQUENTIAL_PARALLELISM);
        assertThat(report.workers()).isEqualTo(1);
        assertThat(report.hasAnalysis()).isFalse();
        verify(prefixAnalyzer, never())
                .profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("fetchAllObjects captures a fallback report when the analysis fails")
    void reportCapturesAnalysisFallback() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects("server", "bucket", "root/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("root/1.txt")), null, false));
        when(prefixAnalyzer.profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("sampling down"));

        AtomicReference<ListingReport> reportRef = new AtomicReference<>();
        service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> { }, reportRef::set);

        ListingReport report = reportRef.get();
        assertThat(report.decision()).isEqualTo(ListingReport.Decision.SEQUENTIAL_FALLBACK);
        assertThat(report.workers()).isEqualTo(4);
        assertThat(report.hasAnalysis()).isFalse();
    }

    @Test
    @DisplayName("fetchAllObjects reports a cached analysis as a cache hit")
    void reportMarksCachedAnalysis() {
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), List.of("a/", "b/"), null, false));
        when(s3StoragePort.listObjects(eq("server"), eq("bucket"), anyString(), any(), anyLong()))
                .thenReturn(new ObjectListing(List.of(), null, false));
        when(prefixAnalyzer.profileFor(anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(PrefixProfile.of(2, List.of(
                        new PrefixProfile.Sample("a/", BucketeerService.LISTING_PAGE, false, 1),
                        new PrefixProfile.Sample("b/", BucketeerService.LISTING_PAGE, false, 1)),
                        Instant.now()).asCached());

        AtomicReference<ListingReport> reportRef = new AtomicReference<>();
        service.fetchAllObjects("server", "bucket", "root/", 0,
                page -> { }, reportRef::set);

        assertThat(reportRef.get().decision()).isEqualTo(ListingReport.Decision.PARALLEL);
        assertThat(reportRef.get().analysisCached()).isTrue();
    }

    @Test
    @DisplayName("fetchAllObjects runs the configured number of workers concurrently")
    void fetchParallelRunsConfiguredWorkerCount() {
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            prefixes.add("p%02d/".formatted(i));
        }
        when(s3StoragePort.listObjectsWithLevel(eq("server"), eq("bucket"), eq("root/"), any(), anyLong()))
                .thenReturn(new LevelListing(List.of(), prefixes, null, false));

        CountDownLatch gate = new CountDownLatch(4);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(s3StoragePort.listObjects(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    gate.countDown();
                    try {
                        // hold this slot until four workers are inside a listing at once
                        gate.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    active.decrementAndGet();
                    return new ObjectListing(List.of(), null, false);
                });

        service.fetchAllObjects("server", "bucket", "root/", 0, page -> { });

        assertThat(maxActive.get())
                .as("the configured parallelism must actually run as concurrent workers")
                .isGreaterThanOrEqualTo(4);
        verify(s3StoragePort, times(20))
                .listObjects(anyString(), eq("bucket"), anyString(), any(), anyLong());
    }

    private static S3Object obj(String key) {
        return new S3Object(key, "bucket", 10L, Instant.parse("2026-01-01T00:00:00Z"), "etag");
    }
}
