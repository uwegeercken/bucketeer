package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.template.LabelTemplate;
import io.github.uwegeercken.bucketeer.domain.template.LabelTemplateSubstitutor;
import io.github.uwegeercken.bucketeer.domain.template.PrefixTemplateEngine;
import io.github.uwegeercken.bucketeer.domain.template.function.TemplateFunction;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsControllerTest {

    private final AppSettings appSettings = mock(AppSettings.class);
    private final DuckDbRepository duckDbRepository = mock(DuckDbRepository.class);
    private final PrefixTemplateEngine templateEngine = mock(PrefixTemplateEngine.class);
    private final LabelTemplateSubstitutor substitutor = mock(LabelTemplateSubstitutor.class);
    private final List<TemplateFunction> functions = List.of(
            new TestFunction("left", 1, "Takes a prefix",
                    List.of("{left(key, 4)} → first 4 chars", "{left(key, 2)}")),
            new TestFunction("upper", 0, "Uppercases the input", List.of()));
    private final SettingsController controller = new SettingsController(
            appSettings, duckDbRepository, templateEngine, substitutor, functions);

    /** Minimal TemplateFunction for the settings payload tests. */
    private record TestFunction(String name, int argCount, String description, List<String> examples)
            implements TemplateFunction {
        @Override public int expectedArgCount() { return argCount; }
        @Override public String apply(String resolvedRef, List<String> args) { return resolvedRef; }
    }

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
    @DisplayName("saveSettings saving the time zone rebuilds the objects_enriched view")
    void saveSettingsTimezoneRebuildsEnrichedView() {
        when(appSettings.toMap()).thenReturn(Map.of("timeZoneId", "Europe/Berlin"));

        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of("timeZoneId", "Europe/Berlin"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(appSettings).setTimeZoneId("Europe/Berlin");
        verify(duckDbRepository).recreateViews();
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

    @Test
    @DisplayName("getSettings exposes the template functions with arg counts and split examples for the variable editor")
    void getSettingsExposesTemplateFunctions() {
        when(appSettings.toMap()).thenReturn(Map.of());

        Map<String, Object> body = controller.getSettings();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> functionList = (List<Map<String, Object>>) body.get("templateFunctions");
        assertThat(functionList).extracting(m -> m.get("name")).contains("left", "upper");

        Map<String, Object> left = functionList.stream()
                .filter(m -> "left".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(left.get("description")).isEqualTo("Takes a prefix");
        assertThat(left.get("argCount")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> examples = (List<Map<String, String>>) left.get("examples");
        assertThat(examples).hasSize(2);
        assertThat(examples.get(0)).containsEntry("expr", "{left(key, 4)}")
                .containsEntry("note", "first 4 chars");
        assertThat(examples.get(1)).containsEntry("expr", "{left(key, 2)}")
                .containsEntry("note", "");

        Map<String, Object> upper = functionList.stream()
                .filter(m -> "upper".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(upper.get("examples")).asList().isEmpty();
    }

    @Test
    @DisplayName("saveSettings persists valid prefix variables")
    void saveSettingsPersistsPrefixVariables() {
        when(substitutor.substitute(anyString(), anyMap()))
                .thenReturn(new LabelTemplateSubstitutor.SubstitutionResult("{left(key,4)}", List.of()));
        when(templateEngine.validate("{left(key,4)}")).thenReturn(List.of());
        when(appSettings.toMap()).thenReturn(Map.of());

        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of(
                "labelTemplates", List.of(Map.of("name", "year", "template", "{left(key,4)}"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(appSettings).setLabelTemplates(List.of(new LabelTemplate("year", "{left(key,4)}")));
    }

    @Test
    @DisplayName("saveSettings rejects prefix variables whose templates use unknown functions")
    void saveSettingsRejectsInvalidVariables() {
        when(substitutor.substitute(anyString(), anyMap()))
                .thenReturn(new LabelTemplateSubstitutor.SubstitutionResult("{nope(key)}", List.of()));
        when(templateEngine.validate("{nope(key)}")).thenReturn(List.of("nope"));

        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of(
                "labelTemplates", List.of(Map.of("name", "bad", "template", "{nope(key)}"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) resp.getBody().get("error")).contains("nope");
        verify(appSettings, never()).setLabelTemplates(any());
    }

    @Test
    @DisplayName("saveSettings rejects a non-list labelTemplates payload")
    void saveSettingsRejectsNonListVariables() {
        ResponseEntity<Map<String, Object>> resp = controller.saveSettings(Map.of("labelTemplates", "nope"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(appSettings, never()).setLabelTemplates(any());
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
        org.mockito.Mockito.verify(duckDbRepository, org.mockito.Mockito.never()).recreateViews();
    }
}