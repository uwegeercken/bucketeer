package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.domain.template.TemplateParseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyCheckControllerTest {

    private S3StoragePort storage;
    private BucketeerUseCase useCase;
    private ThreadPoolTaskExecutor executor;
    private KeyCheckController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        storage = mock(S3StoragePort.class);
        useCase = mock(BucketeerUseCase.class);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.initialize();
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSelectedServer("serverA");
        controller = new KeyCheckController(storage, sessionContext, executor, useCase);
        session = new MockHttpSession();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "keys.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // ---- parseKeyLines ----

    @Test
    void parsesTwoColumnLinesWithLiteralPrefix() throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("data/2024/,file1.parquet\ndata/2025/,file2.parquet\n"), ',', false);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).prefix()).isEqualTo("data/2024/");
        assertThat(lines.get(0).key()).isEqualTo("file1.parquet");
        assertThat(lines.get(0).error()).isNull();
        assertThat(lines.get(1).prefix()).isEqualTo("data/2025/");
    }

    @Test
    void parsesTemplatePrefixAndSkipsHeader() throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("prefix,key\ndata/{date(yyyy/MM)}/,a.txt\n, b.txt\n"), ',', true);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).prefix()).isEqualTo("data/{date(yyyy/MM)}/");
        assertThat(lines.get(0).key()).isEqualTo("a.txt");
        assertThat(lines.get(1).prefix()).isEmpty();
        assertThat(lines.get(1).key()).isEqualTo("b.txt");
    }

    @Test
    void singleColumnLineIsReportedAsError() throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("data/,file1.parquet\nfile2.parquet\n"), ',', false);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).error()).isNull();
        assertThat(lines.get(1).error()).contains("Expected two columns");
        assertThat(lines.get(1).rawLine()).isEqualTo("file2.parquet");
    }

    @Test
    void emptyKeyColumnWithPrefixIsReportedAsError() throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("data/,\n"), ',', false);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).prefix()).isEqualTo("data/");
        assertThat(lines.get(0).error()).isEqualTo("Missing key in second column");
    }

    @Test
    void blankLinesAreSkipped() throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("\n\ndata/,file1.parquet\n\n"), ',', false);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).key()).isEqualTo("file1.parquet");
    }

    @Test
    void keyContainingDelimiterIsJoinedIntoKeyColumn() throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("data/,a,b.csv\n"), ',', false);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).prefix()).isEqualTo("data/");
        assertThat(lines.get(0).key()).isEqualTo("a,b.csv");
    }

    @Test
    void commasInsidePrefixTemplateBracesAreNotSplit() throws Exception {
        String line = "prd/test123/{everyNth(p4,0,2)}/MTA0MTA2LzcyLzM2LzE,MTA0MTA2LzcyLzM2Lz--10.json";
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(file(line + "\n"), ',', false);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).prefix()).isEqualTo("prd/test123/{everyNth(p4,0,2)}/MTA0MTA2LzcyLzM2LzE");
        assertThat(lines.get(0).key()).isEqualTo("MTA0MTA2LzcyLzM2Lz--10.json");
        assertThat(lines.get(0).error()).isNull();
    }

    @Test
    void keyWithBracesStillSplitsIntoTwoColumns() throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("data/,file-{1}.json\n"), ',', false);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).prefix()).isEqualTo("data/");
        assertThat(lines.get(0).key()).isEqualTo("file-{1}.json");
    }

    // ---- assembleFullKey ----

    @Test
    void emptyPrefixReturnsKeyAsIs() {
        assertThat(controller.assembleFullKey("", "file1.parquet", "bucket")).isEqualTo("file1.parquet");
    }

    @Test
    void resolvedPrefixWithoutTrailingSlashGetsSlashAdded() {
        when(useCase.resolveTemplate("data", "file1.parquet", "bucket")).thenReturn("data");

        assertThat(controller.assembleFullKey("data", "file1.parquet", "bucket"))
                .isEqualTo("data/file1.parquet");
    }

    @Test
    void resolvedPrefixWithTrailingSlashIsNotDuplicated() {
        when(useCase.resolveTemplate("data/2024/", "file1.parquet", "bucket")).thenReturn("data/2024/");

        assertThat(controller.assembleFullKey("data/2024/", "file1.parquet", "bucket"))
                .isEqualTo("data/2024/file1.parquet");
    }

    @Test
    void templateIsResolvedWithRowKeyAndBucket() {
        when(useCase.resolveTemplate("data/{left(key, 2)}/", "ab12.txt", "bucket"))
                .thenReturn("data/ab");

        assertThat(controller.assembleFullKey("data/{left(key, 2)}/", "ab12.txt", "bucket"))
                .isEqualTo("data/ab/ab12.txt");
    }

    @Test
    void resolutionFailurePropagates() {
        when(useCase.resolveTemplate("data/{foo()}/", "a.txt", "bucket"))
                .thenThrow(new TemplateParseException("Unknown function 'foo'"));

        assertThatThrownBy(() -> controller.assembleFullKey("data/{foo()}/", "a.txt", "bucket"))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("Unknown function");
    }

    // ---- checkKeys ----

    @Test
    void literalPrefixHeadIsCalledWithCombinedKey() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.headObject(eq("serverA"), eq("bucket"), eq("data/file1.parquet")))
                .thenReturn(new HeadObjectResult(true, 10L, Instant.parse("2026-01-01T00:00:00Z"), "etag1"));
        when(storage.headObject(eq("serverA"), eq("bucket"), eq("data/file2.parquet")))
                .thenReturn(HeadObjectResult.notFound());

        Map<String, Object> result = controller.checkKeys(
                file("data/,file1.parquet\ndata/,file2.parquet\n"), ",", false, "bucket", session);

        List<?> rows = (List<?>) result.get("results");
        assertThat(rows).hasSize(2);
        Map<?, ?> row0 = (Map<?, ?>) rows.get(0);
        Map<?, ?> row1 = (Map<?, ?>) rows.get(1);
        assertThat(row0.get("key")).isEqualTo("data/file1.parquet");
        assertThat(row0.get("prefix")).isEqualTo("data/");
        assertThat(row0.get("rawKey")).isEqualTo("file1.parquet");
        assertThat(row0.get("exists")).isEqualTo(true);
        assertThat(row1.get("key")).isEqualTo("data/file2.parquet");
        assertThat(row1.get("exists")).isEqualTo(false);
        assertThat(result.get("existCount")).isEqualTo(1L);
        assertThat(result.get("missingCount")).isEqualTo(1L);
        verify(storage).headObject(eq("serverA"), eq("bucket"), eq("data/file1.parquet"));
        verify(storage).headObject(eq("serverA"), eq("bucket"), eq("data/file2.parquet"));
    }

    @Test
    void prefixWithoutTrailingSlashBuildsCorrectFullKey() throws Exception {
        when(useCase.resolveTemplate(eq("data"), any(), any())).thenReturn("data");
        when(storage.headObject(eq("serverA"), eq("bucket"), any())).thenReturn(HeadObjectResult.notFound());

        controller.checkKeys(file("data,file1.parquet\n"), ",", false, "bucket", session);

        verify(storage).headObject(eq("serverA"), eq("bucket"), eq("data/file1.parquet"));
    }

    @Test
    void templateWithCommaArgumentsReachesHeadWithAssembledKey() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.headObject(eq("serverA"), eq("bucket"), any())).thenReturn(HeadObjectResult.notFound());

        controller.checkKeys(file("prd/test123/{everyNth(p4,0,2)}/X,abc.json\n"), ",", false, "bucket", session);

        verify(storage).headObject(eq("serverA"), eq("bucket"),
                eq("prd/test123/{everyNth(p4,0,2)}/X/abc.json"));
    }

    @Test
    void singleColumnLineProducesErrorRowWithoutHead() throws Exception {
        when(storage.headObject(any(), any(), any())).thenReturn(HeadObjectResult.notFound());

        Map<String, Object> result = controller.checkKeys(
                file("file1.parquet\n"), ",", false, "bucket", session);

        List<?> rows = (List<?>) result.get("results");
        assertThat(rows).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertThat(row.get("key")).isEqualTo("file1.parquet");
        assertThat(row.get("exists")).isEqualTo(false);
        assertThat(row.get("error")).isNotNull();
        verify(storage, never()).headObject(any(), any(), any());
    }

    @Test
    void invalidPrefixTemplateProducesErrorRowWithoutHead() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any()))
                .thenThrow(new TemplateParseException("Unknown function 'foo'"));
        when(storage.headObject(any(), any(), any())).thenReturn(HeadObjectResult.notFound());

        Map<String, Object> result = controller.checkKeys(
                file("data/{foo()}/,a.txt\n"), ",", false, "bucket", session);

        List<?> rows = (List<?>) result.get("results");
        assertThat(rows).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertThat(row.get("key")).isEqualTo("data/{foo()}/,a.txt");
        assertThat(row.get("exists")).isEqualTo(false);
        assertThat(String.valueOf(row.get("error"))).contains("Unknown function");
        verify(storage, never()).headObject(any(), any(), any());
    }

    // ---- exportCsv ----

    @Test
    void exportIncludesPrefixColumnForReImport() throws Exception {
        session.setAttribute("keycheck_results", List.of(Map.of(
                "key", "data/file1.parquet",
                "prefix", "data/{date(yyyy)}/",
                "rawKey", "file1.parquet",
                "exists", true,
                "sizeBytes", 12345L,
                "lastModified", "2026-07-22T10:00:00Z",
                "etag", "abc123")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportCsv(",", true, response, session);

        String csv = response.getContentAsString();
        assertThat(csv).startsWith("prefix,key,exists,size_bytes,last_modified,etag\n");
        assertThat(csv).contains("data/{date(yyyy)}/,file1.parquet,true,12345,2026-07-22T10:00:00Z,abc123");
    }
}