package io.github.uwegeercken.bucketeer.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppSettingsTest {

    @Test
    @DisplayName("queryParallelism falls back to the application.yml default while nothing is stored")
    void parallelismDefaultsToConfiguredValue() {
        AppSettings settings = new AppSettings(tempPath(), 4);

        assertThat(settings.getQueryParallelism()).isEqualTo(4);
        assertThat(settings.toMap()).containsEntry("queryParallelism", 4);
    }

    @Test
    @DisplayName("queryParallelism stores a value from the settings dialog")
    void parallelismStoresDialogValue() {
        AppSettings settings = new AppSettings(tempPath(), 4);

        settings.setQueryParallelism(12);

        assertThat(settings.getQueryParallelism()).isEqualTo(12);
    }

    @Test
    @DisplayName("queryParallelism is clamped to the maximum worker count")
    void parallelismIsClamped() {
        AppSettings settings = new AppSettings(tempPath(), 4);

        settings.setQueryParallelism(500);

        assertThat(settings.getQueryParallelism()).isEqualTo(AppSettings.MAX_QUERY_PARALLELISM);
    }

    @Test
    @DisplayName("a negative queryParallelism restores the application.yml default")
    void negativeParallelismRestoresYmlDefault() {
        AppSettings settings = new AppSettings(tempPath(), 6);
        settings.setQueryParallelism(12);

        settings.setQueryParallelism(-1);

        assertThat(settings.getQueryParallelism()).isEqualTo(6);
    }

    @Test
    @DisplayName("queryParallelism survives a restart and keeps the yml link while unset")
    void parallelismIsPersisted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        AppSettings first = new AppSettings(file, 4);

        first.setQueryParallelism(9);
        first.setDuckdbQuackEnabled(true);

        AppSettings reloaded = new AppSettings(file, 4);
        assertThat(reloaded.getQueryParallelism()).isEqualTo(9);

        // unset must not be frozen into the file, otherwise the yml default would be lost
        reloaded.setQueryParallelism(-1);
        AppSettings afterReset = new AppSettings(file, 4);
        assertThat(afterReset.getQueryParallelism()).isEqualTo(4);
        assertThat(afterReset.isDuckdbQuackEnabled()).isTrue();
    }

    @Test
    @DisplayName("queryParallelism 0 is a valid setting and means sequential listing")
    void zeroParallelismIsKept(@TempDir Path dir) {
        Path file = dir.resolve("settings.json");
        AppSettings settings = new AppSettings(file, 4);

        settings.setQueryParallelism(0);

        assertThat(settings.getQueryParallelism()).isZero();
        assertThat(new AppSettings(file, 4).getQueryParallelism())
                .as("0 must not be confused with 'unset' on reload")
                .isZero();
    }

    @Test
    @DisplayName("querySampleSize defaults to 8 while nothing is stored")
    void sampleSizeDefaultsToEight() {
        AppSettings settings = new AppSettings(tempPath(), 4);

        assertThat(settings.getQuerySampleSize())
                .isEqualTo(AppSettings.DEFAULT_QUERY_SAMPLE_SIZE);
        assertThat(settings.toMap()).containsEntry("querySampleSize", 8);
    }

    @Test
    @DisplayName("querySampleSize stores a value from the settings dialog")
    void sampleSizeStoresDialogValue() {
        AppSettings settings = new AppSettings(tempPath(), 4);

        settings.setQuerySampleSize(24);

        assertThat(settings.getQuerySampleSize()).isEqualTo(24);
    }

    @Test
    @DisplayName("querySampleSize is clamped to 1..64")
    void sampleSizeIsClamped() {
        AppSettings settings = new AppSettings(tempPath(), 4);

        settings.setQuerySampleSize(500);
        assertThat(settings.getQuerySampleSize())
                .isEqualTo(AppSettings.MAX_QUERY_SAMPLE_SIZE);

        settings.setQuerySampleSize(0);
        assertThat(settings.getQuerySampleSize())
                .as("0 is outside the supported range and becomes the minimum")
                .isOne();
    }

    @Test
    @DisplayName("a negative querySampleSize restores the default")
    void negativeSampleSizeRestoresDefault() {
        AppSettings settings = new AppSettings(tempPath(), 4);
        settings.setQuerySampleSize(32);

        settings.setQuerySampleSize(-1);

        assertThat(settings.getQuerySampleSize())
                .isEqualTo(AppSettings.DEFAULT_QUERY_SAMPLE_SIZE);
    }

    @Test
    @DisplayName("querySampleSize survives a restart and keeps the reset marker")
    void sampleSizeIsPersisted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        AppSettings first = new AppSettings(file, 4);

        first.setQuerySampleSize(16);
        assertThat(new AppSettings(file, 4).getQuerySampleSize()).isEqualTo(16);

        // unset must not be frozen into the file as a plain value
        new AppSettings(file, 4).setQuerySampleSize(-1);
        assertThat(new AppSettings(file, 4).getQuerySampleSize())
                .isEqualTo(AppSettings.DEFAULT_QUERY_SAMPLE_SIZE);
    }

    private static Path tempPath() {
        return Path.of(System.getProperty("java.io.tmpdir"),
                "bucketeer-appsettings-" + System.nanoTime() + ".json");
    }
}