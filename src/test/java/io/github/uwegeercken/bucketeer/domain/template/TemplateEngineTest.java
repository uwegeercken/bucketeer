package io.github.uwegeercken.bucketeer.domain.template;

import io.github.uwegeercken.bucketeer.domain.template.function.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TemplateEngineTest {

    private PrefixTemplateEngine engine;

    @BeforeEach
    void setUp() {
        TemplateParser parser = new TemplateParser();
        List<TemplateFunction> functions = List.of(
                new ShortenedKeyFunction(),
                new LeftFunction(),
                new RightFunction(),
                new SubstringFunction(),
                new UpperFunction(),
                new LowerFunction(),
                new DateFunction()
        );
        TemplateResolver resolver = new TemplateResolver(functions);
        engine = new PrefixTemplateEngine(parser, resolver);
    }

    // --- Category 1: Literals ---

    @Test
    @DisplayName("T01: plain literal path, no placeholders")
    void t01_literalPath() {
        assertThat(engine.resolve("data/2024/01/ABCDEFGH/foo.json", null))
                .isEqualTo("data/2024/01/ABCDEFGH/foo.json");
    }

    @Test
    @DisplayName("T02: base prefix only, key empty")
    void t02_basePrefixOnly() {
        assertThat(engine.resolve("data/", null))
                .isEqualTo("data/");
    }

    @Test
    @DisplayName("T03: empty template returns empty string")
    void t03_emptyTemplate() {
        assertThat(engine.resolve("", null)).isEqualTo("");
        assertThat(engine.resolve(null, null)).isEqualTo("");
    }

    // --- Category 2: key reference ---

    @Test
    @DisplayName("T04: shortenedKey(key) + key")
    void t04_shortenedKeyAndKey() {
        assertThat(engine.resolve("data/{shortenedKey(key)}/{key}/", "MTIzLzQ1Ni83ODkvMDEy"))
                .isEqualTo("data/MILQN8OkME/MTIzLzQ1Ni83ODkvMDEy/");
    }

    @Test
    @DisplayName("T05: upper(key)")
    void t05_upperKey() {
        assertThat(engine.resolve("data/{upper(key)}/", "abcdef"))
                .isEqualTo("data/ABCDEF/");
    }

    @Test
    @DisplayName("T06: left(key, 4)")
    void t06_leftKey() {
        assertThat(engine.resolve("data/{left(key, 4)}/{key}/", "ABCDEFGH"))
                .isEqualTo("data/ABCD/ABCDEFGH/");
    }

    @Test
    @DisplayName("T07: right(key, 3)")
    void t07_rightKey() {
        assertThat(engine.resolve("data/{right(key, 3)}/{key}/", "ABCDEFGH"))
                .isEqualTo("data/FGH/ABCDEFGH/");
    }

    @Test
    @DisplayName("T08: substring(key, 2, 4)")
    void t08_substringKey() {
        assertThat(engine.resolve("data/{substring(key, 2, 4)}/{key}/", "ABCDEFGH"))
                .isEqualTo("data/CDEF/ABCDEFGH/");
    }

    // --- Category 3: pN reference on literal ---

    @Test
    @DisplayName("T09: shortenedKey(p3) where p3 is literal")
    void t09_shortenedKeyP3() {
        assertThat(engine.resolve("data/{shortenedKey(p3)}/ABCDEFGH/", null))
                .isEqualTo("data/ACEG/ABCDEFGH/");
    }

    @Test
    @DisplayName("T10: left(p3,4) and shortenedKey(p3) both on same literal")
    void t10_leftAndShortenedKeyP3() {
        assertThat(engine.resolve("data/{left(p3, 4)}/{shortenedKey(p3)}/ABCDEFGH/", null))
                .isEqualTo("data/ABCD/ACEG/ABCDEFGH/");
    }

    @Test
    @DisplayName("T11: date + shortenedKey(p2) where p2 is literal")
    void t11_dateAndShortenedKeyP2() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("{date(yyyy/MM/dd)}/{shortenedKey(p3)}/ABCDEFGH/", null))
                .isEqualTo(today + "/ACEG/ABCDEFGH/");
    }

    // --- Category 4: date ---

    @Test
    @DisplayName("T12: date(yyyy/MM/dd) - current date")
    void t12_dateToday() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("data/{date(yyyy/MM/dd)}/", null))
                .isEqualTo("data/" + today + "/");
    }

    @Test
    @DisplayName("T13: date(yyyy/MM/dd, -1d) - yesterday")
    void t13_dateYesterday() {
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("data/{date(yyyy/MM/dd, -1d)}/", null))
                .isEqualTo("data/" + yesterday + "/");
    }

    @Test
    @DisplayName("T14: date(yyyyMMdd, +1w) - next week")
    void t14_dateNextWeek() {
        String nextWeek = LocalDate.now().plusWeeks(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(engine.resolve("data/{date(yyyyMMdd, +1w)}/", null))
                .isEqualTo("data/" + nextWeek + "/");
    }

    @Test
    @DisplayName("T15: date(yyyy/MM, -1M) - last month")
    void t15_dateLastMonth() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy/MM"));
        assertThat(engine.resolve("data/{date(yyyy/MM, -1M)}/", null))
                .isEqualTo("data/" + lastMonth + "/");
    }

    // --- Category 5: wildcard (prefix resolution only - wildcard handled by caller) ---

    @Test
    @DisplayName("T16: key with wildcard - template resolves normally, wildcard stripped for prefix")
    void t16_wildcardInKey() {
        // the engine resolves the template; wildcard handling is the controller's responsibility
        // key with wildcard: shortenedKey strips the '*' as it's just another char - document this behaviour
        String result = engine.resolve("data/{shortenedKey(key)}/{key}/", "ABCDE*");
        assertThat(result).isEqualTo("data/AD*/ABCDE*/");
    }

    @Test
    @DisplayName("T17: plain prefix with key wildcard for listing")
    void t17_plainPrefixWithWildcard() {
        assertThat(engine.resolve("data/prefix/", null))
                .isEqualTo("data/prefix/");
    }

    // --- Category 6: errors ---

    @Test
    @DisplayName("T18: unknown function → TemplateParseException")
    void t18_unknownFunction() {
        assertThatThrownBy(() -> engine.resolve("data/{unknownFunc(key)}/", "abc"))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("Unknown function 'unknownFunc'");
    }

    @Test
    @DisplayName("T19: wrong argument count → TemplateFunctionException")
    void t19_wrongArgCount() {
        assertThatThrownBy(() -> engine.resolve("data/{left(key)}/", "abc"))
                .isInstanceOf(TemplateFunctionException.class)
                .hasMessageContaining("left");
    }

    @Test
    @DisplayName("T20: pN references itself (function call at same position) → TemplateParseException")
    void t20_selfReference() {
        assertThatThrownBy(() -> engine.resolve("data/{shortenedKey(p2)}/{key}/", "abc"))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("not-yet-resolved");
    }

    @Test
    @DisplayName("T21: pN out of range → TemplateParseException")
    void t21_outOfRange() {
        assertThatThrownBy(() -> engine.resolve("data/{shortenedKey(p99)}/ABCDEFGH/", null))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("T22: non-numeric argument → TemplateFunctionException")
    void t22_nonNumericArg() {
        assertThatThrownBy(() -> engine.resolve("data/{left(key, abc)}/", "ABCDEFGH"))
                .isInstanceOf(TemplateFunctionException.class)
                .hasMessageContaining("numeric");
    }

    @Test
    @DisplayName("T23: substring len > string length → truncates")
    void t23_substringTruncates() {
        assertThat(engine.resolve("data/{substring(key, 2, 100)}/", "ABCD"))
                .isEqualTo("data/CD/");
    }

    // --- Additional edge cases ---

    @Test
    @DisplayName("left(key, n) where n > length → returns full string")
    void leftTruncates() {
        assertThat(engine.resolve("data/{left(key, 100)}/", "ABCD"))
                .isEqualTo("data/ABCD/");
    }

    @Test
    @DisplayName("right(key, n) where n > length → returns full string")
    void rightTruncates() {
        assertThat(engine.resolve("data/{right(key, 100)}/", "ABCD"))
                .isEqualTo("data/ABCD/");
    }

    @Test
    @DisplayName("substring start > length → returns empty string")
    void substringStartBeyondEnd() {
        assertThat(engine.resolve("data/{substring(key, 10, 4)}/", "ABCD"))
                .isEqualTo("data//");
    }

    @Test
    @DisplayName("lower(key) converts to lowercase")
    void lowerKey() {
        assertThat(engine.resolve("data/{lower(key)}/", "ABCdef"))
                .isEqualTo("data/abcdef/");
    }

    @Test
    @DisplayName("escaped \\{ renders as literal {")
    void escapedBrace() {
        assertThat(engine.resolve("data/\\{notAPlaceholder}/", null))
                .isEqualTo("data/{notAPlaceholder}/");
    }

    @Test
    @DisplayName("validate returns empty list for valid template")
    void validateValid() {
        assertThat(engine.validate("data/{shortenedKey(key)}/{key}/")).isEmpty();
    }

    @Test
    @DisplayName("validate returns unknown function names")
    void validateUnknown() {
        assertThat(engine.validate("data/{foo(key)}/{bar(key)}/"))
                .containsExactlyInAnyOrder("foo", "bar");
    }
}
