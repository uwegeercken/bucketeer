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
                new EveryNthCharFunction(),
                new LeftFunction(),
                new RightFunction(),
                new SubstringFunction(),
                new UpperFunction(),
                new LowerFunction(),
                new DateFunction(),
                new RepeatFunction()
        );
        TemplateResolver resolver = new TemplateResolver(functions);
        engine = new PrefixTemplateEngine(parser, resolver);
    }

    // --- Category 1: Literals ---

    @Test
    @DisplayName("T01: plain literal path, no placeholders")
    void t01_literalPath() {
        assertThat(engine.resolve("data/2024/01/ABCDEFGH/foo.json", null, null))
                .isEqualTo("data/2024/01/ABCDEFGH/foo.json");
    }

    @Test
    @DisplayName("T02: base prefix only, key empty")
    void t02_basePrefixOnly() {
        assertThat(engine.resolve("data/", null, null))
                .isEqualTo("data/");
    }

    @Test
    @DisplayName("T03: empty template returns empty string")
    void t03_emptyTemplate() {
        assertThat(engine.resolve("", null, null)).isEqualTo("");
        assertThat(engine.resolve(null, null, null)).isEqualTo("");
    }

    // --- Category 2: key reference ---

    @Test
    @DisplayName("T04: everyNth(key) + key")
    void t04_everyNthAndKey() {
        assertThat(engine.resolve("data/{everyNth(key, 0, 2)}/", "MTIzLzQ1Ni83ODkvMDEy", null))
                .isEqualTo("data/MILQN8OkME/");
    }

    @Test
    @DisplayName("T05: upper(key)")
    void t05_upperKey() {
        assertThat(engine.resolve("data/{upper(key)}/", "abcdef", null))
                .isEqualTo("data/ABCDEF/");
    }

    @Test
    @DisplayName("T06: left(key, 4)")
    void t06_leftKey() {
        assertThat(engine.resolve("data/{left(key, 4)}/", "ABCDEFGH", null))
                .isEqualTo("data/ABCD/");
    }

    @Test
    @DisplayName("T07: right(key, 3)")
    void t07_rightKey() {
        assertThat(engine.resolve("data/{right(key, 3)}/", "ABCDEFGH", null))
                .isEqualTo("data/FGH/");
    }

    @Test
    @DisplayName("T08: substring(key, 2, 4)")
    void t08_substringKey() {
        assertThat(engine.resolve("data/{substring(key, 2, 4)}/", "ABCDEFGH", null))
                .isEqualTo("data/CDEF/");
    }

    // --- Category 3: pN reference on literal ---

    @Test
    @DisplayName("T09: everyNth(p3) where p3 is literal")
    void t09_everyNthP3() {
        assertThat(engine.resolve("data/{everyNth(p3, 0, 2)}/ABCDEFGH/", null, null))
                .isEqualTo("data/ACEG/ABCDEFGH/");
    }

    @Test
    @DisplayName("T10: left(p4,4) and everyNth(p4) both on same literal")
    void t10_leftAndShortenedKeyP3() {
        assertThat(engine.resolve("data/{left(p4, 4)}/{everyNth(p4, 0, 2)}/ABCDEFGH/", null, null))
                .isEqualTo("data/ABCD/ACEG/ABCDEFGH/");
    }

    @Test
    @DisplayName("T11: date + everyNth(p2) where p2 is literal")
    void t11_dateAndShortenedKeyP2() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("{date(yyyy/MM/dd)}/{everyNth(p3, 0, 2)}/ABCDEFGH/", null, null))
                .isEqualTo(today + "/ACEG/ABCDEFGH/");
    }

    // --- Category 4: date ---

    @Test
    @DisplayName("T12: date(yyyy/MM/dd) - current date")
    void t12_dateToday() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("data/{date(yyyy/MM/dd)}/", null, null))
                .isEqualTo("data/" + today + "/");
    }

    @Test
    @DisplayName("T13: date(yyyy/MM/dd, -1d) - yesterday")
    void t13_dateYesterday() {
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("data/{date(yyyy/MM/dd, -1d)}/", null, null))
                .isEqualTo("data/" + yesterday + "/");
    }

    @Test
    @DisplayName("T14: date(yyyyMMdd, +1w) - next week")
    void t14_dateNextWeek() {
        String nextWeek = LocalDate.now().plusWeeks(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(engine.resolve("data/{date(yyyyMMdd, +1w)}/", null, null))
                .isEqualTo("data/" + nextWeek + "/");
    }

    @Test
    @DisplayName("T15: date(yyyy/MM, -1M) - last month")
    void t15_dateLastMonth() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy/MM"));
        assertThat(engine.resolve("data/{date(yyyy/MM, -1M)}/", null, null))
                .isEqualTo("data/" + lastMonth + "/");
    }

    // --- Category 5: wildcard (prefix resolution only - wildcard handled by caller) ---

    @Test
    @DisplayName("T16: key with wildcard - template resolves normally, wildcard stripped for prefix")
    void t16_wildcardInKey() {
        // the engine resolves the template; wildcard handling is the controller's responsibility
        // key with wildcard: everyNth strips the '*' as it's just another char - document this behaviour
        String result = engine.resolve("data/{everyNth(key, 0, 2)}/", "ABCDE*", null);
        assertThat(result).isEqualTo("data/ACE/");
    }

    @Test
    @DisplayName("T17: plain prefix with key wildcard for listing")
    void t17_plainPrefixWithWildcard() {
        assertThat(engine.resolve("data/prefix/", null, null))
                .isEqualTo("data/prefix/");
    }

    // --- Category 6: errors ---

    @Test
    @DisplayName("T18: unknown function → TemplateParseException")
    void t18_unknownFunction() {
        assertThatThrownBy(() -> engine.resolve("data/{unknownFunc(key)}/", "abc", null))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("Unknown function 'unknownFunc'");
    }

    @Test
    @DisplayName("T19: wrong argument count → TemplateFunctionException")
    void t19_wrongArgCount() {
        assertThatThrownBy(() -> engine.resolve("data/{left(key)}/", "abc", null))
                .isInstanceOf(TemplateFunctionException.class)
                .hasMessageContaining("left");
    }

    @Test
    @DisplayName("T20: pN references itself (function call at same position) → TemplateParseException")
    void t20_selfReference() {
        assertThatThrownBy(() -> engine.resolve("data/{everyNth(p2, 0, 2)}/{key}/", "abc", null))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("not-yet-resolved");
    }

    @Test
    @DisplayName("T21: pN out of range → TemplateParseException")
    void t21_outOfRange() {
        assertThatThrownBy(() -> engine.resolve("data/{everyNth(p99)}/ABCDEFGH/", null, null))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("T22: non-numeric argument → TemplateFunctionException")
    void t22_nonNumericArg() {
        assertThatThrownBy(() -> engine.resolve("data/{left(key, abc)}/", "ABCDEFGH", null))
                .isInstanceOf(TemplateFunctionException.class)
                .hasMessageContaining("numeric");
    }

    @Test
    @DisplayName("T23: substring len > string length → truncates")
    void t23_substringTruncates() {
        assertThat(engine.resolve("data/{substring(key, 2, 100)}/", "ABCD", null))
                .isEqualTo("data/CD/");
    }

    // --- Additional edge cases ---

    @Test
    @DisplayName("left(key, n) where n > length → returns full string")
    void leftTruncates() {
        assertThat(engine.resolve("data/{left(key, 100)}/", "ABCD", null))
                .isEqualTo("data/ABCD/");
    }

    @Test
    @DisplayName("right(key, n) where n > length → returns full string")
    void rightTruncates() {
        assertThat(engine.resolve("data/{right(key, 100)}/", "ABCD", null))
                .isEqualTo("data/ABCD/");
    }

    @Test
    @DisplayName("substring start > length → returns empty string")
    void substringStartBeyondEnd() {
        assertThat(engine.resolve("data/{substring(key, 10, 4)}/", "ABCD", null))
                .isEqualTo("data//");
    }

    @Test
    @DisplayName("lower(key) converts to lowercase")
    void lowerKey() {
        assertThat(engine.resolve("data/{lower(key)}/", "ABCdef", null))
                .isEqualTo("data/abcdef/");
    }

    @Test
    @DisplayName("escaped \\{ renders as literal {")
    void escapedBrace() {
        assertThat(engine.resolve("data/\\{notAPlaceholder}/", null, null))
                .isEqualTo("data/{notAPlaceholder}/");
    }

    @Test
    @DisplayName("T30: date pattern containing slashes is not split as segments")
    void t30_datePatternWithSlashes() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("data/{date(yyyy/MM/dd)}/test/", null, null))
                .isEqualTo("data/" + today + "/test/");
    }

    @Test
    @DisplayName("T31: date with slash pattern and offset")
    void t31_dateWithSlashPatternAndOffset() {
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("logs/{date(yyyy/MM/dd, -1d)}/errors/", null, null))
                .isEqualTo("logs/" + yesterday + "/errors/");
    }

    @Test
    @DisplayName("validate returns empty list for valid template")
    void validateValid() {
        assertThat(engine.validate("data/{everyNth(key, 0, 2)}/")).isEmpty();
    }

    @Test
    @DisplayName("validate returns unknown function names")
    void validateUnknown() {
        assertThat(engine.validate("data/{foo(key)}/{bar(key)}/"))
                .containsExactlyInAnyOrder("foo", "bar");
    }

    // --- Category 7: function chaining ---

    @Test
    @DisplayName("T24: upper(everyNth(key, 0, 2)) - chain two functions")
    void t24_upperEveryNth() {
        assertThat(engine.resolve("data/{upper(everyNth(key, 0, 2))}/", "abcdefgh", null))
                .isEqualTo("data/ACEG/");
    }

    @Test
    @DisplayName("T25: lower(left(key, 5)) - chain left then lower")
    void t25_lowerLeft() {
        assertThat(engine.resolve("data/{lower(left(key, 5))}/", "ABCDEFGH", null))
                .isEqualTo("data/abcde/");
    }

    @Test
    @DisplayName("T26: left(everyNth(key, 0, 2), 4) - chain everyNth then left")
    void t26_leftEveryNth() {
        assertThat(engine.resolve("data/{left(everyNth(key, 0, 2), 4)}/", "ABCDEFGHIJ", null))
                .isEqualTo("data/ACEG/");
    }

    @Test
    @DisplayName("T27: upper(date(yyyy/MM/dd)) - chain date then upper")
    void t27_upperDate() {
        // date returns e.g. "2026/07/04" - upper has no effect on digits/slashes
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("data/{upper(date(yyyy/MM/dd))}/", null, null))
                .isEqualTo("data/" + today + "/");
    }

    @Test
    @DisplayName("T28: upper(everyNth(p3, 0, 2)) where p3 is literal")
    void t28_upperEveryNthP3() {
        assertThat(engine.resolve("data/{upper(everyNth(p3, 0, 2))}/abcdefgh/", null, null))
                .isEqualTo("data/ACEG/abcdefgh/");
    }

    @Test
    @DisplayName("T29: unknown nested function → TemplateParseException")
    void t29_unknownNestedFunction() {
        assertThatThrownBy(() -> engine.resolve("data/{upper(unknownFn(key))}/", "abc", null))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("unknownFn");
    }

    @Test
    @DisplayName("validate detects unknown function in nested call")
    void validateNestedUnknown() {
        assertThat(engine.validate("data/{upper(unknownFn(key))}/"))
                .containsExactly("unknownFn");
    }

    // --- Category 8: function call with literal suffix ---

    @Test
    @DisplayName("T30: {left(p2,5)}-test - function call with literal suffix")
    void t30_functionWithSuffix() {
        assertThat(engine.resolve("testdata/events/shard-00/{left(p2,5)}-test", "abcdefghij", null))
                .isEqualTo("testdata/events/shard-00/event-test");
    }

    @Test
    @DisplayName("T31: {left(key,3)}suffix - function on key with literal suffix")
    void t31_functionOnKeyWithSuffix() {
        assertThat(engine.resolve("data/{left(key,3)}-output", "hello", null))
                .isEqualTo("data/hel-output");
    }

    @Test
    @DisplayName("T32: {upper(key)}.json - function with literal extension")
    void t32_functionWithExtension() {
        assertThat(engine.resolve("files/{upper(key)}.json", "report", null))
                .isEqualTo("files/REPORT.json");
    }

    @Test
    @DisplayName("T33: {left(p1,3)}-test/suffix - suffix then separator, ref to literal")
    void t33_suffixThenSeparator() {
        assertThat(engine.resolve("xxxxx/{left(p1,3)}-test/b", "xxxxxxxxx", null))
                .isEqualTo("xxxxx/xxx-test/b");
    }

    // --- Category 9: bucket reference ---

    @Test
    @DisplayName("T34: upper(bucket) - bucket name uppercased")
    void t34_upperBucket() {
        assertThat(engine.resolve("data/{upper(bucket)}/", null, "my-bucket"))
                .isEqualTo("data/MY-BUCKET/");
    }

    @Test
    @DisplayName("T35: repeat(bucket) - bucket name inserted directly")
    void t35_repeatBucket() {
        assertThat(engine.resolve("{repeat(bucket)}/data/", null, "logs-eu"))
                .isEqualTo("logs-eu/data/");
    }

    @Test
    @DisplayName("T36: bucket with date")
    void t36_bucketWithDate() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(engine.resolve("{repeat(bucket)}/{date(yyyy/MM/dd)}/", null, "my-bucket"))
                .isEqualTo("my-bucket/" + today + "/");
    }

    @Test
    @DisplayName("T37: bucket is null → empty string")
    void t37_bucketNull() {
        assertThat(engine.resolve("data/{upper(bucket)}/", null, null))
                .isEqualTo("data//");
    }

    // --- Category 10: repeat function ---

    @Test
    @DisplayName("T38: repeat(key) - key inserted directly")
    void t38_repeatKey() {
        assertThat(engine.resolve("{repeat(key)}/archive/", "doc.pdf", null))
                .isEqualTo("doc.pdf/archive/");
    }

    @Test
    @DisplayName("T39: repeat(p4) where p4 is literal")
    void t39_repeatP4() {
        assertThat(engine.resolve("abc/{repeat(p4)}/ghi/jkl", null, null))
                .isEqualTo("abc/jkl/ghi/jkl");
    }

    @Test
    @DisplayName("T40: upper(repeat(p2)) - chaining with repeat where p2 is literal")
    void t40_upperRepeatP2() {
        assertThat(engine.resolve("hello/{upper(repeat(p1))}/", null, null))
                .isEqualTo("hello/HELLO/");
    }

    @Test
    @DisplayName("T41: repeat(p4) forward reference to literal → allowed")
    void t41_repeatForwardToLiteral() {
        assertThat(engine.resolve("a/{repeat(p3)}/b/{repeat(p3)}/c", null, null))
                .isEqualTo("a/b/b/b/c");
    }

    @Test
    @DisplayName("T42: repeat(p2) where p2 is function at same position → forward reference error")
    void t42_repeatForwardToFunction() {
        assertThatThrownBy(() -> engine.resolve("{repeat(p1)}/{upper(key)}/", "abc", null))
                .isInstanceOf(TemplateParseException.class)
                .hasMessageContaining("not-yet-resolved");
    }

    @Test
    @DisplayName("T43: repeat(p2) where p2 is function at lower position → allowed")
    void t43_repeatBackRefToFunction() {
        assertThat(engine.resolve("{upper(key)}/{repeat(p1)}/", "abc", null))
                .isEqualTo("ABC/ABC/");
    }

    @Test
    @DisplayName("T44: repeat without ref argument → resolves to empty string")
    void t44_repeatNoArgs() {
        assertThat(engine.resolve("data/{repeat()}/", null, null))
                .isEqualTo("data//");
    }

    @Test
    @DisplayName("T45: validate recognizes repeat and bucket")
    void t45_validateRepeatAndBucket() {
        assertThat(engine.validate("data/{repeat(p1)}/{upper(bucket)}/")).isEmpty();
    }
}
