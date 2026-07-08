package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {substring(ref, start, len)} → substring from start (0-based), length len; truncates if needed */
@Component
public class SubstringFunction implements TemplateFunction {

    @Override public String name() { return "substring"; }
    @Override public int expectedArgCount() { return 2; }

    @Override
    public String description() {
        return "Returns a substring starting at position start (0-based) with the given length. " +
               "Truncates if the input is shorter than start + length. " +
               "Returns an empty string if start is beyond the end of the input.";
    }

    @Override
    public List<String> examples() {
        return List.of(
                "{substring(key, 2, 4)}  → e.g. \"ABCDEFGH\" → \"CDEF\"",
                "{substring(key, 0, 8)}  → e.g. \"ABCDEFGH\" → \"ABCDEFGH\"",
                "{substring(key, 4, 100)}→ e.g. \"ABCDEFGH\" → \"EFGH\" (truncated)"
        );
    }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        if (resolvedRef == null || resolvedRef.isBlank()) return "";
        int start = LeftFunction.parsePositiveInt(args.get(0), "substring");
        int len   = LeftFunction.parsePositiveInt(args.get(1), "substring");
        if (start >= resolvedRef.length()) return "";
        int end = Math.min(start + len, resolvedRef.length());
        return resolvedRef.substring(start, end);
    }
}
