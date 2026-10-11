package io.github.uwegeercken.bucketeer.adapter.in.web;

import io.github.uwegeercken.bucketeer.domain.template.LabelTemplate;
import io.github.uwegeercken.bucketeer.domain.template.LabelTemplateSubstitutor;
import io.github.uwegeercken.bucketeer.domain.template.PrefixTemplateEngine;
import io.github.uwegeercken.bucketeer.domain.template.function.TemplateFunction;
import io.github.uwegeercken.bucketeer.infrastructure.config.AppSettings;
import io.github.uwegeercken.bucketeer.infrastructure.db.DuckDbRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class SettingsController {

    private static final List<String> COMMON_ZONES = List.of(
            "UTC",
            "Europe/Berlin",
            "Europe/London",
            "Europe/Paris",
            "Europe/Vienna",
            "Europe/Zurich",
            "America/New_York",
            "America/Chicago",
            "America/Los_Angeles",
            "America/Sao_Paulo",
            "Asia/Tokyo",
            "Asia/Singapore",
            "Asia/Shanghai",
            "Australia/Sydney"
    );

    private final AppSettings appSettings;

    private final DuckDbRepository duckDbRepository;

    private final PrefixTemplateEngine templateEngine;

    private final LabelTemplateSubstitutor labelTemplateSubstitutor;

    private final List<TemplateFunction> templateFunctions;

    public SettingsController(AppSettings appSettings, DuckDbRepository duckDbRepository,
                              PrefixTemplateEngine templateEngine,
                              LabelTemplateSubstitutor labelTemplateSubstitutor,
                              List<TemplateFunction> templateFunctions) {
        this.appSettings = appSettings;
        this.duckDbRepository = duckDbRepository;
        this.templateEngine = templateEngine;
        this.labelTemplateSubstitutor = labelTemplateSubstitutor;
        this.templateFunctions = templateFunctions.stream()
                .sorted(Comparator.comparing(TemplateFunction::name))
                .toList();
    }

    @GetMapping(value = "/api/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getSettings() {
        Map<String, Object> body = new LinkedHashMap<>(appSettings.toMap());
        body.put("systemTimeZoneId", ZoneId.systemDefault().getId());
        body.put("timeZoneOptions", COMMON_ZONES);
        body.put("templateFunctions", templateFunctions.stream()
                .map(f -> Map.<String, Object>of(
                        "name", f.name(),
                        "description", f.description(),
                        "argCount", f.expectedArgCount(),
                        "examples", f.examples().stream().map(SettingsController::splitExample).toList()))
                .toList());
        return body;
    }

    /**
     * Splits a help-page example (e.g. "{left(key, 4)} → first 4 chars") into the insertable
     * expression and an optional explanatory note, so the editor can offer a correct snippet.
     */
    private static Map<String, String> splitExample(String example) {
        int arrow = example == null ? -1 : example.indexOf('→');
        if (arrow < 0) {
            return Map.of("expr", example == null ? "" : example.trim(), "note", "");
        }
        return Map.of("expr", example.substring(0, arrow).trim(),
                "note", example.substring(arrow + 1).trim());
    }

    @PostMapping(value = "/api/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body) {
        // Validate and persist prefix variables first: an invalid variable list rejects
        // the whole save (400) instead of leaving the other settings half-applied.
        if (body.containsKey("labelTemplates")) {
            Object val = body.get("labelTemplates");
            if (!(val instanceof List<?> list)) {
                return ResponseEntity.badRequest().body(Map.of("error", "labelTemplates must be a list"));
            }
            List<LabelTemplate> templates = parseLabelTemplates(list);
            List<String> problems = validateLabelTemplates(templates);
            if (!problems.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Invalid prefix variables: " + String.join("; ", problems)));
            }
            appSettings.setLabelTemplates(templates);
        }
        if (body.containsKey("snapshotRetentionDays")) {
            Object val = body.get("snapshotRetentionDays");
            if (val instanceof Number n) {
                appSettings.setSnapshotRetentionDays(n.intValue());
            }
        }
        if (body.containsKey("timeZoneId")) {
            Object tz = body.get("timeZoneId");
            if (tz instanceof String s) {
                appSettings.setTimeZoneId(s);
            }
        }
        if (body.containsKey("maxFileSizeMb")) {
            Object val = body.get("maxFileSizeMb");
            if (val instanceof Number n) {
                appSettings.setMaxFileSizeMb(n.intValue());
            }
        }
        if (body.containsKey("maxRequestSizeMb")) {
            Object val = body.get("maxRequestSizeMb");
            if (val instanceof Number n) {
                appSettings.setMaxRequestSizeMb(n.intValue());
            }
        }
        if (body.containsKey("duckdbQuackEnabled")) {
            Object val = body.get("duckdbQuackEnabled");
            if (val instanceof Boolean b) {
                appSettings.setDuckdbQuackEnabled(b);
                duckDbRepository.updateQuackServer();
            }
        }
        if (body.containsKey("queryParallelism")) {
            Object val = body.get("queryParallelism");
            if (val instanceof Number n) {
                appSettings.setQueryParallelism(n.intValue());
            }
        }
        if (body.containsKey("querySampleSize")) {
            Object val = body.get("querySampleSize");
            if (val instanceof Number n) {
                appSettings.setQuerySampleSize(n.intValue());
            }
        }
        return ResponseEntity.ok(appSettings.toMap());
    }

    private static List<LabelTemplate> parseLabelTemplates(List<?> list) {
        List<LabelTemplate> templates = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("name") instanceof String name
                    && m.get("template") instanceof String template) {
                templates.add(new LabelTemplate(name, template));
            }
        }
        return templates;
    }

    /**
     * Validates a complete set of prefix variables: names must be valid and unique, the
     * count must stay within the limit, and every template must expand without unknown
     * variables, cycles or unknown functions (checked against the new set, so
     * cross-references within the same save are validated too).
     */
    private List<String> validateLabelTemplates(List<LabelTemplate> templates) {
        List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (LabelTemplate t : templates) {
            if (!LabelTemplate.isValidName(t.name())) {
                problems.add("Invalid variable name '" + t.name()
                        + "' (use a-z0-9_ only, max 32 characters)");
            } else if (!names.add(t.name())) {
                problems.add("Duplicate variable name '" + t.name() + "'");
            }
        }
        if (templates.size() > AppSettings.MAX_PREFIX_VARIABLES) {
            problems.add("Too many prefix variables (max " + AppSettings.MAX_PREFIX_VARIABLES + ")");
        }
        if (problems.isEmpty()) {
            Map<String, String> variables = templates.stream()
                    .collect(Collectors.toMap(LabelTemplate::name, LabelTemplate::template,
                            (a, b) -> a, LinkedHashMap::new));
            for (LabelTemplate t : templates) {
                LabelTemplateSubstitutor.SubstitutionResult result =
                        labelTemplateSubstitutor.substitute(t.template(), variables);
                result.problems().forEach(p -> problems.add(t.name() + ": " + p));
                templateEngine.validate(result.text()).forEach(f ->
                        problems.add(t.name() + ": Unknown function: " + f));
            }
        }
        return problems.stream().distinct().toList();
    }
}
