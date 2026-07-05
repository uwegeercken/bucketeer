package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {everyNth(ref, start, step)} → characters at positions start, start+step, start+2*step, ...
 *
 * Examples:
 *   everyNth(key, 0, 2)  → characters at index 0, 2, 4, ...  (equivalent to former shortenedKey)
 *   everyNth(key, 1, 2)  → characters at index 1, 3, 5, ...
 *   everyNth(key, 0, 3)  → characters at index 0, 3, 6, ...
 */
@Component
public class EveryNthCharFunction implements TemplateFunction {

    @Override
    public String name() { return "everyNth"; }

    @Override
    public int expectedArgCount() { return 2; }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        if (resolvedRef == null || resolvedRef.isBlank()) return "";
        int start = LeftFunction.parsePositiveInt(args.get(0), "everyNth");
        int step  = LeftFunction.parsePositiveInt(args.get(1), "everyNth");
        if (step < 1) throw new TemplateFunctionException(
                "Function 'everyNth': step must be >= 1, got: " + step);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < resolvedRef.length(); i += step) {
            sb.append(resolvedRef.charAt(i));
        }
        return sb.toString();
    }
}