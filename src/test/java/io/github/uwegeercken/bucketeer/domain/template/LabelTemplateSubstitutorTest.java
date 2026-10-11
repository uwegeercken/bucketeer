package io.github.uwegeercken.bucketeer.domain.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabelTemplateSubstitutorTest {

    private static final Map<String, String> YEAR = Map.of("year", "{left(key, 4)}");

    private final LabelTemplateSubstitutor substitutor = new LabelTemplateSubstitutor();

    @Test
    @DisplayName("replaces a variable reference with its template text")
    void replacesSimpleReference() {
        var result = substitutor.substitute("data/@{year}/", YEAR);

        assertThat(result.hasProblems()).isFalse();
        assertThat(result.text()).isEqualTo("data/{left(key, 4)}/");
    }

    @Test
    @DisplayName("leaves templates without references untouched")
    void leavesPlainTemplatesUntouched() {
        var result = substitutor.substitute("data/2024/", YEAR);

        assertThat(result.hasProblems()).isFalse();
        assertThat(result.text()).isEqualTo("data/2024/");
    }

    @Test
    @DisplayName("reports unknown variables and keeps the reference in place")
    void reportsUnknownVariable() {
        var result = substitutor.substitute("data/@{nope}/", YEAR);

        assertThat(result.problems()).containsExactly("Unknown prefix variable: nope");
        assertThat(result.text()).isEqualTo("data/@{nope}/");
    }

    @Test
    @DisplayName("expands variables nested inside other variables")
    void expandsNestedVariables() {
        var result = substitutor.substitute("@{a}", Map.of(
                "a", "@{b}/x",
                "b", "y"));

        assertThat(result.hasProblems()).isFalse();
        assertThat(result.text()).isEqualTo("y/x");
    }

    @Test
    @DisplayName("expands variables inside function arguments")
    void expandsInsideFunctionArguments() {
        var result = substitutor.substitute("{left(@{a}, 2)}",
                Map.of("a", "{substring(key, 1, 2)}"));

        assertThat(result.hasProblems()).isFalse();
        assertThat(result.text()).isEqualTo("{left({substring(key, 1, 2)}, 2)}");
    }

    @Test
    @DisplayName("detects cycles across variables")
    void detectsCycles() {
        var result = substitutor.substitute("@{a}", Map.of("a", "@{b}/x", "b", "@{a}"));

        assertThat(result.problems()).anyMatch(p -> p.contains("Cycle detected"));
    }

    @Test
    @DisplayName("detects self-references")
    void detectsSelfReference() {
        var result = substitutor.substitute("@{a}", Map.of("a", "@{a}"));

        assertThat(result.problems()).anyMatch(p -> p.contains("Cycle detected"));
    }

    @Test
    @DisplayName("aborts expansion that nests deeper than the limit")
    void abortsDeepNesting() {
        Map<String, String> chain = new LinkedHashMap<>();
        for (int i = 0; i <= LabelTemplateSubstitutor.MAX_NESTING_DEPTH; i++) {
            chain.put("v" + i, "@{v" + (i + 1) + "}");
        }
        chain.put("v9", "end");

        var result = substitutor.substitute("@{v0}", chain);

        assertThat(result.problems()).anyMatch(p -> p.contains("nested more than"));
    }

    @Test
    @DisplayName("does not treat a plain @ or an uppercase name as a variable reference")
    void ignoresPlainAtSigns() {
        var result = substitutor.substitute("a@b @{Team} @x", YEAR);

        assertThat(result.hasProblems()).isFalse();
        assertThat(result.text()).isEqualTo("a@b @{Team} @x");
    }

    @Test
    @DisplayName("an escaped literal brace survives substitution")
    void keepsEscapedBraces() {
        var result = substitutor.substitute("\\{foo} @{year}", YEAR);

        assertThat(result.hasProblems()).isFalse();
        assertThat(result.text()).isEqualTo("\\{foo} {left(key, 4)}");
    }

    @Test
    @DisplayName("null templates pass through untouched")
    void nullTemplatePassesThrough() {
        var result = substitutor.substitute(null, Map.of("a", "x"));

        assertThat(result.text()).isNull();
        assertThat(result.hasProblems()).isFalse();
    }
}