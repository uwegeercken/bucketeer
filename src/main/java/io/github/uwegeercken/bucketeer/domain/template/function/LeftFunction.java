package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {left(ref, n)} → first n characters of ref; truncates if ref is shorter */
@Component
public class LeftFunction implements TemplateFunction {

    @Override public String name() { return "left"; }
    @Override public int expectedArgCount() { return 1; }

    @Override
    public String description() {
        return "Returns the first N characters of the input. " +
               "If the input is shorter than N, the full input is returned.";
    }

    @Override
    public List<String> examples() {
        return List.of(
                "{left(key, 4)}          → e.g. \"ABCDEFGH\" → \"ABCD\"",
                "{left(p3, 3)}           → first 3 chars of segment 3",
                "{left(everyNth(key, 0, 2), 4)} → chained: everyNth result, first 4 chars"
        );
    }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        if (resolvedRef == null || resolvedRef.isBlank()) return "";
        int n = parsePositiveInt(args.getFirst(), "left");
        return resolvedRef.substring(0, Math.min(n, resolvedRef.length()));
    }

    static int parsePositiveInt(String value, String functionName) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < 0) throw new TemplateFunctionException(
                    "Function '" + functionName + "': argument must be non-negative, got: " + value);
            return n;
        } catch (NumberFormatException e) {
            throw new TemplateFunctionException(
                    "Function '" + functionName + "': expected numeric argument, got: " + value);
        }
    }
}
