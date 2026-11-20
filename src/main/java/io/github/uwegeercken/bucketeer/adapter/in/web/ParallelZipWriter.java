package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes S3 objects into a ZIP stream while fetching them in parallel.
 *
 * <p>A ZIP download of many small objects is bound by the request round trip, not by bandwidth:
 * fetching them one after another costs {@code count × latency}. This writer keeps up to
 * {@code window} GET requests in flight on a dedicated pool while a single caller thread writes
 * the finished objects into the ZIP <b>in submit order</b>, so the archive layout is identical to
 * a sequential download and the response still streams from the first entry on.
 *
 * <p>Memory stays bounded: each fetch buffers at most {@link #BUFFER_LIMIT} bytes. A larger
 * object hands the rest of its open S3 stream to the writer, which streams it through.
 *
 * <p>Not thread-safe: {@link #submit} and {@link #finish} must be called from one thread at a time.
 * A failed fetch is logged and its entry skipped (as before); a failure while writing to the ZIP
 * (typically the client aborting the download) stops the whole download.
 */
final class ParallelZipWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ParallelZipWriter.class);

    /** Bytes buffered per object before the rest of it is streamed by the writer thread. */
    static final int BUFFER_LIMIT = 1024 * 1024;
    /** GETs for small objects are latency bound, so downloads use at least this many workers. */
    static final int MIN_WORKERS = 8;
    static final int MAX_WORKERS = 32;

    private final S3StoragePort s3StoragePort;
    private final ZipOutputStream zos;
    private final ExecutorService pool;
    private final int workers;
    private final int window;
    private final Deque<Pending> pending = new ArrayDeque<>();
    private final long startNanos = System.nanoTime();
    private int written;
    private int failed;
    private long bytes;

    private record Pending(String bucket, String key, String entryName, Future<InputStream> data) {}

    ParallelZipWriter(S3StoragePort s3StoragePort, ZipOutputStream zos, int workers) {
        this.s3StoragePort = s3StoragePort;
        this.zos = zos;
        this.workers = Math.max(1, workers);
        this.window = this.workers * 2;
        AtomicInteger threadSeq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(this.workers, runnable -> {
            Thread thread = new Thread(runnable, "zip-fetch-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Download worker count derived from the configured query parallelism. */
    static int workerCount(int queryParallelism) {
        return Math.min(MAX_WORKERS, Math.max(MIN_WORKERS, queryParallelism));
    }

    /**
     * Starts fetching one object and, once more than {@code window} fetches are pending, writes
     * the oldest finished ones into the ZIP.
     */
    void submit(String serverName, String bucket, String key, String entryName) {
        pending.add(new Pending(bucket, key, entryName,
                pool.submit(() -> fetch(serverName, bucket, key))));
        while (pending.size() > window) {
            writeNext();
        }
    }

    /** Writes all pending entries and logs a summary. */
    void finish() {
        while (!pending.isEmpty()) {
            writeNext();
        }
        long ms = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000);
        log.info("ZIP download: {} objects ({} bytes) written, {} failed, {} ms, {} objects/s, {} workers",
                written, bytes, failed, ms, written * 1000L / ms, workers);
    }

    private InputStream fetch(String serverName, String bucket, String key) throws IOException {
        InputStream in = s3StoragePort.downloadObject(serverName, bucket, key);
        try {
            byte[] head = in.readNBytes(BUFFER_LIMIT);
            if (head.length < BUFFER_LIMIT) {
                in.close(); // fully read: release the connection right away
                return new ByteArrayInputStream(head);
            }
            return new SequenceInputStream(new ByteArrayInputStream(head), in);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    private void writeNext() {
        Pending p = pending.poll();
        InputStream data;
        try {
            data = p.data().get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Failed to zip object {}/{}: {}", p.bucket(), p.key(), cause.getMessage());
            failed++;
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("ZIP download interrupted"));
        }
        try (InputStream in = data) {
            zos.putNextEntry(new ZipEntry(p.entryName()));
            bytes += in.transferTo(zos);
            zos.closeEntry();
            written++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stops the fetch pool and releases the S3 streams of entries that were never written. */
    @Override
    public void close() {
        pool.shutdownNow();
        for (Pending p : pending) {
            p.data().cancel(true);
            if (p.data().isDone() && !p.data().isCancelled()) {
                try {
                    p.data().get().close();
                } catch (Exception ignored) {
                    // failed fetch or close error: nothing left to release
                }
            }
        }
        pending.clear();
    }
}
