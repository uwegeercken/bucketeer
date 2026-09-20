package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.ObjectListing;
import io.github.uwegeercken.bucketeer.domain.model.S3Object;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiV1ControllerTest {

    private BucketeerUseCase useCase;
    private S3StoragePort storage;
    private BucketeerController bucketeerController;
    private ApiV1Controller controller;

    @BeforeEach
    void setUp() {
        useCase = mock(BucketeerUseCase.class);
        storage = mock(S3StoragePort.class);
        bucketeerController = mock(BucketeerController.class);
        controller = new ApiV1Controller(useCase, storage, bucketeerController);
        when(useCase.serverNames()).thenReturn(List.of("serverA", "serverB"));
    }

    private BucketeerController.SearchTarget target(String s3Prefix, String keyFilter) {
        return new BucketeerController.SearchTarget(s3Prefix, keyFilter);
    }

    private void stubForbidden() {
        when(bucketeerController.searchTarget(any(), any(), any()))
                .thenReturn(target("data/", null));
    }

    @Test
    @DisplayName("servers returns the configured server names")
    void servers() {
        assertThat(controller.servers()).containsExactly("serverA", "serverB");
    }

    @Test
    @DisplayName("buckets lists buckets for a known server and rejects unknown servers")
    void buckets() {
        when(useCase.listBuckets("serverA")).thenReturn(List.of("b1", "b2"));
        ResponseEntity<?> resp = controller.buckets("serverA");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(List.of("b1", "b2"));

        ResponseEntity<?> unknown = controller.buckets("nope");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) unknown.getBody()).get("error")).isEqualTo("Unknown server: nope");
    }

    @Test
    @DisplayName("resolve returns the resolved template text and reports template errors with 400")
    void resolve() {
        when(useCase.resolveTemplate("data/{date(yyyy)}/", "a", "b")).thenReturn("data/2026/");
        ResponseEntity<String> resp = controller.resolve("data/{date(yyyy)}/", "a", "b");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo("data/2026/");

        when(useCase.resolveTemplate("bad/{foo()}/", "a", "b"))
                .thenThrow(new RuntimeException("Unknown function 'foo'"));
        ResponseEntity<String> err = controller.resolve("bad/{foo()}/", "a", "b");
        assertThat(err.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(err.getBody()).contains("Unknown function 'foo'");
    }

    @Test
    @DisplayName("validate reports unknown functions and accepts empty templates")
    void validate() {
        ResponseEntity<?> ok = controller.validate(null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) ok.getBody()).get("valid")).isEqualTo(true);

        when(useCase.validateTemplate("{foo()}")).thenReturn(List.of("foo"));
        ResponseEntity<?> bad = controller.validate("{foo()}");
        assertThat(((Map<?, ?>) bad.getBody()).get("valid")).isEqualTo(false);
        assertThat((String) ((Map<?, ?>) bad.getBody()).get("error")).contains("foo");
    }

    @Test
    @DisplayName("list applies the key filter and skips directory marker keys")
    void listAppliesFilter() {
        when(bucketeerController.searchTarget(eq("data/"), eq("2024/photo"), eq("bucket")))
                .thenReturn(target("data/", "data/2024/photo"));
        when(useCase.fetchAllObjects(eq("serverA"), eq("bucket"), eq("data/"), eq(0L), any()))
                .thenAnswer(inv -> {
                    inv.<java.util.function.Consumer<ObjectListing>>getArgument(4).accept(
                            new ObjectListing(List.of(
                                    new S3Object("data/2024/photo", "bucket", 42, Instant.parse("2026-01-01T00:00:00Z"), "e"),
                                    new S3Object("data/2024/other", "bucket", 7, Instant.parse("2026-01-01T00:00:00Z"), "f"),
                                    new S3Object("data/dir/", "bucket", 0, null, "g")
                            ), null, false));
                    return false;
                });

        ResponseEntity<?> resp = controller.list("serverA", "bucket", "data/", "2024/photo", 0);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> body = (List<?>) resp.getBody();
        assertThat(body).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) body.get(0);
        assertThat(first.get("key")).isEqualTo("data/2024/photo");
        assertThat(first.get("size_bytes")).isEqualTo(42L);
        assertThat(first.get("last_modified")).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("list rejects an unknown server and a missing bucket")
    void listValidates() {
        ResponseEntity<?> unknown = controller.list("nope", "bucket", "data/", null, 0);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<?> noBucket = controller.list("serverA", null, "data/", null, 0);
        assertThat(noBucket.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("download streams the object bytes with a Content-Disposition header")
    void download() throws Exception {
        when(storage.downloadObject("serverA", "bucket", "dir/file.txt"))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.download("serverA", "bucket", "dir/file.txt", response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeader("Content-Disposition")).contains("file.txt");
    }

    @Test
    @DisplayName("download rejects an unknown server")
    void downloadRejectsUnknownServer() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.download("nope", "bucket", "key", response);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("download/prefix zips all matching objects preserving their key paths")
    void downloadPrefix() throws Exception {
        stubForbidden();
        when(useCase.fetchAllObjects(eq("serverA"), eq("bucket"), any(), eq(0L), any()))
                .thenAnswer(inv -> {
                    inv.<java.util.function.Consumer<ObjectListing>>getArgument(4).accept(
                            new ObjectListing(List.of(
                                    new S3Object("data/a.txt", "bucket", 1, null, "e"),
                                    new S3Object("data/b.txt", "bucket", 2, null, "f"),
                                    new S3Object("data/empty/", "bucket", 0, null, "g")
                            ), null, false));
                    return false;
                });
        when(storage.downloadObject("serverA", "bucket", "data/a.txt"))
                .thenReturn(new ByteArrayInputStream("AAA".getBytes(StandardCharsets.UTF_8)));
        when(storage.downloadObject("serverA", "bucket", "data/b.txt"))
                .thenReturn(new ByteArrayInputStream("BBB".getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.downloadPrefix("serverA", "bucket", "data/", null, 0, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/zip");
        assertThat(response.getHeader("Content-Disposition")).contains(".zip");

        java.util.List<String> entries = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.add(entry.getName());
                zis.closeEntry();
            }
        }
        assertThat(entries).containsExactlyInAnyOrder("data/a.txt", "data/b.txt");
    }
}