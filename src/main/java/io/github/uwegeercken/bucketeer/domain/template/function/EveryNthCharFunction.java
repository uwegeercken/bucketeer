package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {everyNth(ref, start, step)} → characters at positions start, start+step, start+2*step, ...
 */
@Component
public class EveryNthCharFunction implements TemplateFunction {

    @Override public String name() { return "everyNth"; }
    @Override public int expectedArgCount() { return 2; }

    @Override
    public String description() {
        return "Returns every Nth character of the input, starting at a given position. " +
               "Useful for distributing objects evenly across S3 prefixes.";
    }

    @Override
    public List<String> examples() {
        return List.of(
                "{everyNth(key, 0, 2)}   → characters at index 0, 2, 4, …  e.g. \"ABCDEFGH\" → \"ACEG\"",
                "{everyNth(key, 1, 2)}   → characters at index 1, 3, 5, …  e.g. \"ABCDEFGH\" → \"BDFH\"",
                "{everyNth(key, 0, 3)}   → characters at index 0, 3, 6, …  e.g. \"ABCDEFGHI\" → \"ADG\""
        );
    }

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
