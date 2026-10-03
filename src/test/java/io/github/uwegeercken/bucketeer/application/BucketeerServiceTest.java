package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.model.LevelListing;
import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.PrefixCount;
import io.github.uwegeercken.bucketeer.domain.model.PrefixScan;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.infrastructure.config.S3Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BucketeerServiceTest {

    private static final S3Properties PROPERTIES =
            new S3Properties("0.8.3", "2026-10-03",
                    new S3Properties.Query(4), new S3Properties.Scan(10, 3));

    private S3StoragePort s3StoragePort;
    private BucketeerService service;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        s3StoragePort = mock(S3StoragePort.class);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.initialize();
        service = new BucketeerService(s3StoragePort, null, PROPERTIES, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
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
    @DisplayName("fetchAllObjects uses a sequential listing when parallelism is disabled")
    void fetchSequentialWhenParallelismDisabled() {
        S3Properties noParallel =
                new S3Properties("0.8.3", "2026-10-03", new S3Properties.Query(0), new S3Properties.Scan(10, 3));
        BucketeerService seqService = new BucketeerService(s3StoragePort, null, noParallel, executor);
        when(s3StoragePort.listObjects("server", "bucket", "data/", null, 0))
                .thenReturn(new ObjectListing(List.of(obj("data/a")), null, false));

        List<S3Object> collected = new ArrayList<>();
        boolean limit = seqService.fetchAllObjects("server", "bucket", "data/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(limit).isFalse();
        assertThat(collected.stream().map(S3Object::key)).containsExactly("data/a");
        verify(s3StoragePort, never()).listObjectsWithLevel(anyString(), anyString(), anyString(), any(), anyLong());

        BucketeerService singleWorker =
                new BucketeerService(s3StoragePort, null,
                        new S3Properties("0.8.3", "2026-10-03", new S3Properties.Query(1), new S3Properties.Scan(10, 3)),
                        executor);
        collected.clear();
        boolean oneLimit = singleWorker.fetchAllObjects("server", "bucket", "data/", 0,
                page -> collected.addAll(page.objects()));

        assertThat(oneLimit).isFalse();
        assertThat(collected.stream().map(S3Object::key)).containsExactly("data/a");
        verify(s3StoragePort, times(2)).listObjects(anyString(), anyString(), anyString(), isNull(), anyLong());
        verify(s3StoragePort, never()).listObjectsWithLevel(anyString(), anyString(), anyString(), any(), anyLong());
    }

    private static S3Object obj(String key) {
        return new S3Object(key, "bucket", 10L, Instant.parse("2026-01-01T00:00:00Z"), "etag");
    }
}
