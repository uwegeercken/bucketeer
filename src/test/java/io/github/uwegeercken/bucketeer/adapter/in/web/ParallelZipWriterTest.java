package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParallelZipWriterTest {

    private final S3StoragePort storage = mock(S3StoragePort.class);

    private static InputStream text(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a ZIP into entry name → content, keeping the entry order. */
    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    @DisplayName("workerCount clamps the query parallelism to the download range 8..32")
    void workerCount() {
        assertThat(ParallelZipWriter.workerCount(0)).isEqualTo(8);
        assertThat(ParallelZipWriter.workerCount(4)).isEqualTo(8);
        assertThat(ParallelZipWriter.workerCount(16)).isEqualTo(16);
        assertThat(ParallelZipWriter.workerCount(100)).isEqualTo(32);
    }

    @Test
    @DisplayName("entries are written in submit order even when later fetches finish first")
    void keepsSubmitOrder() throws Exception {
        int count = 40;
        when(storage.downloadObject(eq("S"), eq("B"), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(2);
            int n = Integer.parseInt(key.substring(1));
            Thread.sleep((count - n) % 7); // earlier keys tend to take longer
            return text("content-" + key);
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out);
             ParallelZipWriter writer = new ParallelZipWriter(storage, zos, 4)) {
            for (int i = 0; i < count; i++) {
                writer.submit("S", "B", "k" + i, "e" + i);
            }
            writer.finish();
        }

        Map<String, byte[]> entries = unzip(out.toByteArray());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            expected.add("e" + i);
        }
        assertThat(entries.keySet()).containsExactlyElementsOf(expected);
        assertThat(new String(entries.get("e7"), StandardCharsets.UTF_8)).isEqualTo("content-k7");
    }

    @Test
    @DisplayName("several objects are fetched concurrently")
    void fetchesConcurrently() throws Exception {
        CountDownLatch allStarted = new CountDownLatch(4);
        when(storage.downloadObject(eq("S"), eq("B"), anyString())).thenAnswer(inv -> {
            allStarted.countDown();
            // a sequential writer would deadlock here: only 4 parallel fetches release the latch
            if (!allStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fetches did not run in parallel");
            }
            return text("x");
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out);
             ParallelZipWriter writer = new ParallelZipWriter(storage, zos, 4)) {
            for (int i = 0; i < 4; i++) {
                writer.submit("S", "B", "k" + i, "e" + i);
            }
            writer.finish();
        }

        assertThat(unzip(out.toByteArray())).hasSize(4);
    }

    @Test
    @DisplayName("an object larger than the buffer limit is streamed completely and its S3 stream closed")
    void streamsLargeObject() throws Exception {
        byte[] large = new byte[ParallelZipWriter.BUFFER_LIMIT * 2 + 123];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }
        AtomicInteger closed = new AtomicInteger();
        when(storage.downloadObject("S", "B", "big")).thenReturn(
                new FilterInputStream(new ByteArrayInputStream(large)) {
                    @Override
                    public void close() throws IOException {
                        closed.incrementAndGet();
                        super.close();
                    }
                });
        when(storage.downloadObject("S", "B", "small")).thenReturn(text("tiny"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out);
             ParallelZipWriter writer = new ParallelZipWriter(storage, zos, 2)) {
            writer.submit("S", "B", "big", "big.bin");
            writer.submit("S", "B", "small", "small.txt");
            writer.finish();
        }

        Map<String, byte[]> entries = unzip(out.toByteArray());
        assertThat(entries.keySet()).containsExactly("big.bin", "small.txt");
        assertThat(Arrays.equals(entries.get("big.bin"), large)).isTrue();
        assertThat(closed.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a failed fetch skips only that entry")
    void skipsFailedObject() throws Exception {
        when(storage.downloadObject("S", "B", "a")).thenReturn(text("A"));
        when(storage.downloadObject("S", "B", "b")).thenThrow(new RuntimeException("NoSuchKey"));
        when(storage.downloadObject("S", "B", "c")).thenReturn(text("C"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out);
             ParallelZipWriter writer = new ParallelZipWriter(storage, zos, 2)) {
            writer.submit("S", "B", "a", "a");
            writer.submit("S", "B", "b", "b");
            writer.submit("S", "B", "c", "c");
            writer.finish();
        }

        assertThat(unzip(out.toByteArray()).keySet()).containsExactly("a", "c");
    }

    @Test
    @DisplayName("a write failure (client abort) stops the download")
    void stopsOnWriteFailure() throws Exception {
        when(storage.downloadObject(eq("S"), eq("B"), anyString())).thenAnswer(inv -> text("data"));
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
        };

        try (ZipOutputStream zos = new ZipOutputStream(broken);
             ParallelZipWriter writer = new ParallelZipWriter(storage, zos, 2)) {
            assertThatThrownBy(() -> {
                for (int i = 0; i < 100; i++) {
                    writer.submit("S", "B", "k" + i, "e" + i);
                }
                writer.finish();
            }).isInstanceOf(UncheckedIOException.class).hasMessageContaining("Broken pipe");
        } catch (IOException ignored) {
            // closing the ZipOutputStream on the broken stream fails as well
        }
    }

    @Test
    @DisplayName("zipEntryName strips the folder and numbers repeated file names")
    void zipEntryName() {
        Map<String, Integer> names = new HashMap<>();
        assertThat(CartController.zipEntryName(names, "a/b/file.txt")).isEqualTo("file.txt");
        assertThat(CartController.zipEntryName(names, "c/file.txt")).isEqualTo("file-2.txt");
        assertThat(CartController.zipEntryName(names, "x/README")).isEqualTo("README");
        assertThat(CartController.zipEntryName(names, "y/README")).isEqualTo("README-2");
    }
}
