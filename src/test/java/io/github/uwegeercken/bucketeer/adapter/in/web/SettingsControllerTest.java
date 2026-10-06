package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsControllerTest {

    private final AppSettings appSettings = mock(AppSettings.class);
    private final DuckDbRepository duckDbRepository = mock(DuckDbRepository.class);
    private final SettingsController controller = new SettingsController(appSettings, duckDbRepository);

    @Test
    @DisplayName("getSettings exposes server settings plus timezone options and the system timezone")
    void getSettingsExposesTimezoneOptions() {
        when(appSettings.toMap()).thenReturn(Map.of(
                "snapshotRetentionDays", 30,
                "timeZoneId", "Europe/Berlin",
                "maxFileSizeMb", 100,
                "maxRequestSizeMb", 500,
                "queryParallelism", 8));

        Map<String, Object> body = controller.getSettings();

        assertThat(body.get("snapshotRetentionDays")).isEqualTo(30);
        assertThat(body.get("timeZoneId")).isEqualTo("Europe/Berlin");
        assertThat(body.get("maxFileSizeMb")).isEqualTo(100);
        assertThat(body.get("maxRequestSizeMb")).isEqualTo(500);
        assertThat(body.get("queryParallelism")).isEqualTo(8);
        assertThat(body.get("systemTimeZoneId")).isEqualTo(java.time.ZoneId.systemDefault().getId());
        assertThat(body.get("timeZoneOptions")).asList().contains("UTC", "Europe/Berlin");
    }

    @Test
    @DisplayName("saveSettings applies known keys to AppSettings and echoes the new state")
    void saveSettingsAppliesValues() {
        when(appSettings.toMap()).thenReturn(Map.of(
                "snapshotRetentionDays", 40,
                "timeZoneId", "Europe/Berlin",
                "maxFileSizeMb", 200,
                "maxRequestSizeMb", 800));

        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of(
                "snapshotRetentionDays", 40,
                "timeZoneId", "Europe/Berlin",
                "maxFileSizeMb", 200,
                "maxRequestSizeMb", 800));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(appSettings).setSnapshotRetentionDays(40);
        verify(appSettings).setTimeZoneId("Europe/Berlin");
        verify(appSettings).setMaxFileSizeMb(200);
        verify(appSettings).setMaxRequestSizeMb(800);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    @DisplayName("saveSettings ignores unknown keys and non-numeric values")
    void saveSettingsIgnoresUnknownKeys() {
        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of(
                "snapshotRetentionDays", "not-a-number",
                "bogus", "x"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoSettersCalled();
    }

    @Test
    @DisplayName("saveSettings toggling duckdbQuackEnabled persists the flag and reconciles the Quack server")
    void saveSettingsTogglesQuackServer() {
        when(appSettings.toMap()).thenReturn(Map.of(
                "snapshotRetentionDays", 30,
                "timeZoneId", "UTC",
                "maxFileSizeMb", 100,
                "maxRequestSizeMb", 500,
                "duckdbQuackEnabled", true));

        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of("duckdbQuackEnabled", true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(appSettings).setDuckdbQuackEnabled(true);
        verify(duckDbRepository).updateQuackServer();
    }

    @Test
    @DisplayName("saveSettings applies the parallel listing worker count")
    void saveSettingsAppliesQueryParallelism() {
        when(appSettings.toMap()).thenReturn(Map.of("queryParallelism", 12));

        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of("queryParallelism", 12));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(appSettings).setQueryParallelism(12);
    }

    @Test
    @DisplayName("saveSettings accepts a negative parallel listing worker count to fall back to the yml default")
    void saveSettingsAcceptsNegativeQueryParallelism() {
        when(appSettings.toMap()).thenReturn(Map.of("queryParallelism", 4));

        controller.saveSettings(Map.of("queryParallelism", -1));

        verify(appSettings).setQueryParallelism(-1);
    }

    @Test
    @DisplayName("saveSettings applies the prefix analysis sample count")
    void saveSettingsAppliesQuerySampleSize() {
        when(appSettings.toMap()).thenReturn(Map.of("querySampleSize", 16));

        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of("querySampleSize", 16));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(appSettings).setQuerySampleSize(16);
    }

    @Test
    @DisplayName("saveSettings accepts a negative sample count to fall back to the default")
    void saveSettingsAcceptsNegativeQuerySampleSize() {
        when(appSettings.toMap()).thenReturn(Map.of("querySampleSize", 8));

        controller.saveSettings(Map.of("querySampleSize", -1));

        verify(appSettings).setQuerySampleSize(-1);
    }

    private void verifyNoSettersCalled() {
        org.mockito.Mockito.verify(appSettings, org.mockito.Mockito.never()).setSnapshotRetentionDays(org.mockito.Mockito.anyInt());
        org.mockito.Mockito.verify(appSettings, org.mockito.Mockito.never()).setTimeZoneId(org.mockito.Mockito.anyString());
        org.mockito.Mockito.verify(appSettings, org.mockito.Mockito.never()).setMaxFileSizeMb(org.mockito.Mockito.anyInt());
        org.mockito.Mockito.verify(appSettings, org.mockito.Mockito.never()).setMaxRequestSizeMb(org.mockito.Mockito.anyInt());
        org.mockito.Mockito.verify(appSettings, org.mockito.Mockito.never()).setDuckdbQuackEnabled(org.mockito.Mockito.anyBoolean());
        org.mockito.Mockito.verify(appSettings, org.mockito.Mockito.never()).setQueryParallelism(org.mockito.Mockito.anyInt());
        org.mockito.Mockito.verify(appSettings, org.mockito.Mockito.never()).setQuerySampleSize(org.mockito.Mockito.anyInt());
        org.mockito.Mockito.verify(duckDbRepository, org.mockito.Mockito.never()).updateQuackServer();
    }
}