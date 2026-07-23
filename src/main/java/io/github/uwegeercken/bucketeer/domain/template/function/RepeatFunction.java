package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {repeat(ref)} → returns the resolved reference value unchanged (identity function) */
@Component
public class RepeatFunction implements TemplateFunction {

    @Override public String name() { return "repeat"; }
    @Override public int expectedArgCount() { return 0; }

    @Override
    public String description() { return "Returns the reference value unchanged. Use to embed a segment, key, or bucket directly."; }

    @Override
    public List<String> examples() {
        return List.of(
                "{repeat(key)}           → inserts the key value",
                "{repeat(bucket)}        → inserts the bucket name",
                "{repeat(p4)}            → inserts the value of segment 4"
        );
    }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        return resolvedRef == null ? "" : resolvedRef;
    }
}
