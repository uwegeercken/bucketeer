package io.github.uwegeercken.bucketeer.domain.template;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code @{name}} variable references in a prefix template string.
 *
 * <p>Substitution happens on the raw template text <em>before</em> the template
 * parser sees it, so the referenced template text is parsed exactly as if it had
 * been typed inline. References may appear anywhere, including inside function
 * arguments ({@code {left(@{name}, 2)}}), and variables may reference other
 * variables. Cycles and excessive nesting are detected and reported.
 */
@Component
public class LabelTemplateSubstitutor {

    /** Upper bound on variable expansion levels before the expansion is aborted. */
    public static final int MAX_NESTING_DEPTH = 8;

    private static final Pattern VARIABLE_REF = Pattern.compile("@\\{([a-z0-9_]+)\\}");

    /** Result of a substitution: the expanded text plus any problems found. */
    public record SubstitutionResult(String text, List<String> problems) {

        public boolean hasProblems() {
            return !problems.isEmpty();
        }
    }

    /**
     * Replaces every {@code @{name}} reference in the template with the variable's
     * template text (recursively). Unknown variables, cycles and too-deep nesting
     * are reported in the result's problem list; the unmatched reference is left
     * in place so callers can surface a precise error.
     */
    public SubstitutionResult substitute(String template, Map<String, String> variables) {
        if (template == null || !template.contains("@{")) {
            return new SubstitutionResult(template, List.of());
        }
        List<String> problems = new ArrayList<>();
        String text = expand(template, variables == null ? Map.of() : variables,
                new ArrayList<>(), problems, 0);
        return new SubstitutionResult(text, List.copyOf(problems));
    }

    private String expand(String text, Map<String, String> variables,
                          List<String> chain, List<String> problems, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            problems.add("Prefix variables nested more than " + MAX_NESTING_DEPTH + " levels");
            return text;
        }
        Matcher matcher = VARIABLE_REF.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            out.append(text, last, matcher.start());
            String value = variables.get(name);
            if (value == null) {
                problems.add("Unknown prefix variable: " + name);
                out.append(matcher.group());
            } else if (chain.contains(name)) {
                List<String> cycle = new ArrayList<>(chain);
                cycle.add(name);
                problems.add("Cycle detected in prefix variables: "
                        + String.join(" -> ", cycle));
                out.append(matcher.group());
            } else {
                chain.add(name);
                out.append(expand(value, variables, chain, problems, depth + 1));
                chain.remove(chain.size() - 1);
            }
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }
}