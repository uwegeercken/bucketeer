package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {right(ref, n)} → last n characters of ref; truncates if ref is shorter */
@Component
public class RightFunction implements TemplateFunction {

    @Override
    public String name() { return "right"; }

    @Override
    public int expectedArgCount() { return 1; }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        if (resolvedRef == null || resolvedRef.isBlank()) return "";
        int n = LeftFunction.parsePositiveInt(args.getFirst(), "right");
        int start = Math.max(0, resolvedRef.length() - n);
        return resolvedRef.substring(start);
    }
}
