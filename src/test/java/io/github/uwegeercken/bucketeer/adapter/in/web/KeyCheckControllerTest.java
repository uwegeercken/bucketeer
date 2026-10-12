package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.model.HeadObjectResult;
import io.github.uwegeercken.bucketeer.domain.port.in.BucketeerUseCase;
import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.domain.template.TemplateParseException;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
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
    private AppSettings appSettings;
    private ThreadPoolTaskExecutor executor;
    private KeyCheckController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        storage = mock(S3StoragePort.class);
        useCase = mock(BucketeerUseCase.class);
        appSettings = mock(AppSettings.class);
        when(appSettings.getQueryParallelism()).thenReturn(4);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.initialize();
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSelectedServer("serverA");
        controller = new KeyCheckController(storage, sessionContext, executor, useCase, appSettings);
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

    // ---- runCheck (synchronous core) ----

    /** Parses the given CSV and runs the check synchronously into a fresh job. */
    private KeyCheckJob run(String content, String bucket) throws Exception {
        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(file(content), ',', false);
        KeyCheckJob job = new KeyCheckJob(lines.size());
        controller.runCheck(job, "serverA", bucket, lines);
        return job;
    }

    @Test
    void literalPrefixHeadIsCalledWithCombinedKey() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.headObject(eq("serverA"), eq("bucket"), eq("data/file1.parquet")))
                .thenReturn(new HeadObjectResult(true, 10L, Instant.parse("2026-01-01T00:00:00Z"), "etag1"));
        when(storage.headObject(eq("serverA"), eq("bucket"), eq("data/file2.parquet")))
                .thenReturn(HeadObjectResult.notFound());

        KeyCheckJob job = run("data/,file1.parquet\ndata/,file2.parquet\n", "bucket");

        List<Map<String, Object>> rows = job.getRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("key")).isEqualTo("data/file1.parquet");
        assertThat(rows.get(0).get("prefix")).isEqualTo("data/");
        assertThat(rows.get(0).get("rawKey")).isEqualTo("file1.parquet");
        assertThat(rows.get(0).get("exists")).isEqualTo(true);
        assertThat(rows.get(1).get("key")).isEqualTo("data/file2.parquet");
        assertThat(rows.get(1).get("exists")).isEqualTo(false);
        assertThat(job.getStatus()).isEqualTo(KeyCheckJob.Status.DONE);
        assertThat(job.getProcessed()).isEqualTo(2);
        assertThat(job.getExistCount()).isEqualTo(1L);
        assertThat(job.getMissingCount()).isEqualTo(1L);
        assertThat(job.getErrorCount()).isEqualTo(0L);
        verify(storage).headObject(eq("serverA"), eq("bucket"), eq("data/file1.parquet"));
        verify(storage).headObject(eq("serverA"), eq("bucket"), eq("data/file2.parquet"));
    }

    @Test
    void prefixWithoutTrailingSlashBuildsCorrectFullKey() throws Exception {
        when(useCase.resolveTemplate(eq("data"), any(), any())).thenReturn("data");
        when(storage.headObject(eq("serverA"), eq("bucket"), any())).thenReturn(HeadObjectResult.notFound());

        run("data,file1.parquet\n", "bucket");

        verify(storage).headObject(eq("serverA"), eq("bucket"), eq("data/file1.parquet"));
    }

    @Test
    void templateWithCommaArgumentsReachesHeadWithAssembledKey() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.headObject(eq("serverA"), eq("bucket"), any())).thenReturn(HeadObjectResult.notFound());

        run("prd/test123/{everyNth(p4,0,2)}/X,abc.json\n", "bucket");

        verify(storage).headObject(eq("serverA"), eq("bucket"),
                eq("prd/test123/{everyNth(p4,0,2)}/X/abc.json"));
    }

    @Test
    void singleColumnLineProducesErrorRowWithoutHead() throws Exception {
        when(storage.headObject(any(), any(), any())).thenReturn(HeadObjectResult.notFound());

        KeyCheckJob job = run("file1.parquet\n", "bucket");

        List<Map<String, Object>> rows = job.getRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("key")).isEqualTo("file1.parquet");
        assertThat(rows.get(0).get("exists")).isEqualTo(false);
        assertThat(rows.get(0).get("error")).isNotNull();
        assertThat(job.getErrorCount()).isEqualTo(1L);
        assertThat(job.getMissingCount()).isEqualTo(0L);
        verify(storage, never()).headObject(any(), any(), any());
    }

    @Test
    void invalidPrefixTemplateProducesErrorRowWithoutHead() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any()))
                .thenThrow(new TemplateParseException("Unknown function 'foo'"));
        when(storage.headObject(any(), any(), any())).thenReturn(HeadObjectResult.notFound());

        KeyCheckJob job = run("data/{foo()}/,a.txt\n", "bucket");

        List<Map<String, Object>> rows = job.getRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("key")).isEqualTo("data/{foo()}/,a.txt");
        assertThat(rows.get(0).get("exists")).isEqualTo(false);
        assertThat(String.valueOf(rows.get(0).get("error"))).contains("Unknown function");
        verify(storage, never()).headObject(any(), any(), any());
    }

    @Test
    void cancelledCheckStopsBeforeAnyHead() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        List<KeyCheckController.KeyLine> lines = controller.parseKeyLines(
                file("data/,a.json\ndata/,b.json\n"), ',', false);
        KeyCheckJob job = new KeyCheckJob(lines.size());
        job.cancel();

        controller.runCheck(job, "serverA", "bucket", lines);

        assertThat(job.getStatus()).isEqualTo(KeyCheckJob.Status.CANCELLED);
        assertThat(job.getRows()).isEmpty();
        verify(storage, never()).headObject(any(), any(), any());
    }

    // ---- startCheck (asynchronous start + polling) ----

    @Test
    void startCheckReturnsImmediatelyAndCompletesInBackground() throws Exception {
        // A tiny pool (capacity 1 + 8 threads): the old "one future per line" code aborted on the
        // 10th line. The check now dispatches a single orchestrating task onto it.
        ThreadPoolTaskExecutor smallPool = new ThreadPoolTaskExecutor();
        smallPool.setCorePoolSize(1);
        smallPool.setMaxPoolSize(1);
        smallPool.setQueueCapacity(8);
        smallPool.initialize();
        SessionContext sessionContext = new SessionContext();
        sessionContext.setSelectedServer("serverA");
        KeyCheckController smallController =
                new KeyCheckController(storage, sessionContext, smallPool, useCase, appSettings);

        int lineCount = 300;
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.headObject(eq("serverA"), eq("bucket"), any()))
                .thenReturn(new HeadObjectResult(true, 1L, Instant.parse("2026-01-01T00:00:00Z"), "e"));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            sb.append("data/,file").append(String.format("%04d", i)).append(".json\n");
        }

        try {
            Map<String, Object> response = smallController.startCheck(
                    file(sb.toString()), ",", false, "bucket", session);

            assertThat(response.get("started")).isEqualTo(true);
            assertThat(response.get("total")).isEqualTo(lineCount);

            KeyCheckJob job = awaitFinished();
            assertThat(job.getRows()).hasSize(lineCount);
            assertThat(job.getProcessed()).isEqualTo(lineCount);
            assertThat(job.getExistCount()).isEqualTo((long) lineCount);
            assertThat(job.getMissingCount()).isEqualTo(0L);
        } finally {
            smallPool.shutdown();
        }
    }

    @Test
    void startCheckWithoutServerReturnsError() throws Exception {
        SessionContext noServer = new SessionContext();
        KeyCheckController controllerWithoutServer =
                new KeyCheckController(storage, noServer, executor, useCase, appSettings);

        Map<String, Object> response = controllerWithoutServer.startCheck(
                file("data/,a.json\n"), ",", false, "bucket", session);

        assertThat(response.get("error")).isEqualTo("No server selected");
    }

    private KeyCheckJob awaitFinished() throws InterruptedException {
        KeyCheckJob job = (KeyCheckJob) session.getAttribute(KeyCheckJob.SESSION_KEY);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && job.getStatus() == KeyCheckJob.Status.RUNNING) {
            Thread.sleep(10);
        }
        return job;
    }

    // ---- checkStatus / cancelCheck / checkResults ----

    @Test
    void statusIsIdleWithoutJob() {
        assertThat(controller.checkStatus(session).get("status")).isEqualTo("IDLE");
    }

    @Test
    void statusReportsCountsAndTerminalState() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.headObject(eq("serverA"), eq("bucket"), eq("data/a.json")))
                .thenReturn(new HeadObjectResult(true, 1L, Instant.parse("2026-01-01T00:00:00Z"), "e"));
        when(storage.headObject(eq("serverA"), eq("bucket"), eq("data/b.json")))
                .thenReturn(HeadObjectResult.notFound());

        session.setAttribute(KeyCheckJob.SESSION_KEY, run("data/,a.json\ndata/,b.json\n", "bucket"));

        Map<String, Object> status = controller.checkStatus(session);
        assertThat(status.get("status")).isEqualTo("DONE");
        assertThat(status.get("total")).isEqualTo(2);
        assertThat(status.get("processed")).isEqualTo(2);
        assertThat(status.get("existCount")).isEqualTo(1L);
        assertThat(status.get("missingCount")).isEqualTo(1L);
        assertThat(status.get("errorCount")).isEqualTo(0L);
        assertThat(status.get("cancelling")).isEqualTo(false);
    }

    @Test
    void cancelMarksRunningJobAndReportsCancelling() {
        session.setAttribute(KeyCheckJob.SESSION_KEY, new KeyCheckJob(10));

        Map<String, Object> status = controller.cancelCheck(session);

        assertThat(status.get("cancelling")).isEqualTo(true);
        assertThat(status.get("status")).isEqualTo("RUNNING");
        assertThat(((KeyCheckJob) session.getAttribute(KeyCheckJob.SESSION_KEY)).isCancelled()).isTrue();
    }

    @Test
    void checkResultsPagesAndFiltersRows() throws Exception {
        when(useCase.resolveTemplate(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.headObject(eq("serverA"), eq("bucket"), any()))
                .thenAnswer(inv -> ((String) inv.getArgument(2)).contains("exists")
                        ? new HeadObjectResult(true, 1L, Instant.parse("2026-01-01T00:00:00Z"), "e")
                        : HeadObjectResult.notFound());

        session.setAttribute(KeyCheckJob.SESSION_KEY,
                run("data/,exists1.json\ndata/,missing1.json\ndata/,exists2.json\n", "bucket"));

        Map<String, Object> page0 = controller.checkResults(0, 2, "all", session);
        assertThat((List<?>) page0.get("rows")).hasSize(2);
        assertThat(page0.get("total")).isEqualTo(3);
        assertThat(page0.get("allTotal")).isEqualTo(3);
        assertThat(page0.get("hasMore")).isEqualTo(true);

        Map<String, Object> page1 = controller.checkResults(1, 2, "all", session);
        assertThat((List<?>) page1.get("rows")).hasSize(1);
        assertThat(page1.get("hasMore")).isEqualTo(false);

        assertThat(controller.checkResults(0, 10, "exists", session).get("total")).isEqualTo(2);
        assertThat(controller.checkResults(0, 10, "missing", session).get("total")).isEqualTo(1);
        assertThat(controller.checkResults(0, 10, "error", session).get("total")).isEqualTo(0);
    }

    @Test
    void checkResultsWithoutJobIsEmpty() {
        Map<String, Object> result = controller.checkResults(0, 100, "all", session);

        assertThat((List<?>) result.get("rows")).isEmpty();
        assertThat(result.get("total")).isEqualTo(0);
    }

    // ---- exportCsv ----

    @Test
    void exportIncludesPrefixColumnForReImport() throws Exception {
        KeyCheckJob job = new KeyCheckJob(1);
        job.finish(List.of(Map.of(
                "key", "data/file1.parquet",
                "prefix", "data/{date(yyyy)}/",
                "rawKey", "file1.parquet",
                "exists", true,
                "sizeBytes", 12345L,
                "lastModified", "2026-07-22T10:00:00Z",
                "etag", "abc123")));
        session.setAttribute(KeyCheckJob.SESSION_KEY, job);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportCsv(",", true, response, session);

        String csv = response.getContentAsString();
        assertThat(csv).startsWith("prefix,key,exists,size_bytes,last_modified,etag\n");
        assertThat(csv).contains("data/{date(yyyy)}/,file1.parquet,true,12345,2026-07-22T10:00:00Z,abc123");
    }

    @Test
    void exportStripsEtagQuotesSoTheCsvStaysClean() throws Exception {
        KeyCheckJob job = new KeyCheckJob(1);
        job.finish(List.of(Map.of(
                "key", "data/file1.parquet",
                "prefix", "data/",
                "rawKey", "file1.parquet",
                "exists", true,
                "sizeBytes", 12345L,
                "lastModified", "2026-07-22T10:00:00Z",
                "etag", "\"abc123\"")));
        session.setAttribute(KeyCheckJob.SESSION_KEY, job);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportCsv(",", false, response, session);

        assertThat(response.getContentAsString())
                .isEqualTo("data/,file1.parquet,true,12345,2026-07-22T10:00:00Z,abc123\n");
    }
}