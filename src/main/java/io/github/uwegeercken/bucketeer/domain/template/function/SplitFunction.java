package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {split(ref, delimiter, index)} → splits the input by the delimiter and returns the part at the
 * given 1-based index. Empty parts are kept. Out-of-range indices produce an empty string.
 */
@Component
public class SplitFunction implements TemplateFunction {

    @Override public String name() { return "split"; }
    @Override public int expectedArgCount() { return 2; }

    @Override
    public String description() {
        return "Splits the input by the given delimiter (literal string) and returns the part at the 1-based index. " +
               "Useful for extracting a path segment from a key. Index 1 is the first part; " +
               "empty parts are kept; out-of-range indices produce an empty string.";
    }

    @Override
    public List<String> examples() {
        return List.of(
                "{split(key, /, 2)}    → second part, e.g. \"a/b/c\" → \"b\"",
                "{split(key, -, 1)}    → first part, e.g. \"ABC-DEF\" → \"ABC\"",
                "{split(key, ., 2)}    → e.g. \"data.json\" → \"json\"",
                "{split(p3, _, 2)}     → split a literal segment, e.g. \"a_b_c\" → \"b\""
        );
    }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        if (resolvedRef == null || resolvedRef.isBlank()) return "";

        String delimiter = args.get(0);
        if (delimiter.isEmpty()) {
            throw new TemplateFunctionException("Function 'split': delimiter must not be empty");
        }

        int index = parseIndex(args.get(1));
        List<String> parts = split(resolvedRef, delimiter);
        if (index > parts.size()) return "";
        return parts.get(index - 1);
    }

    private int parseIndex(String raw) {
        int index;
        try {
            index = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new TemplateFunctionException(
                    "Function 'split': expected numeric index, got: " + raw);
        }
        if (index < 1) {
            throw new TemplateFunctionException(
                    "Function 'split': index must be >= 1 (1-based), got: " + index);
        }
        return index;
    }

    private List<String> split(String value, String delimiter) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (true) {
            int found = value.indexOf(delimiter, start);
            if (found < 0) {
                parts.add(value.substring(start));
                break;
            }
            parts.add(value.substring(start, found));
            start = found + delimiter.length();
        }
        return parts;
    }
}