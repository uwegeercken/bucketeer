package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {lower(ref)} → ref in lowercase */
@Component
public class LowerFunction implements TemplateFunction {

    @Override public String name() { return "lower"; }
    @Override public int expectedArgCount() { return 0; }

    @Override
    public String description() { return "Converts the input to lowercase."; }

    @Override
    public List<String> examples() {
        return List.of(
                "{lower(key)}                    → e.g. \"ABCDEF\" → \"abcdef\"",
                "{lower(left(key, 5))}           → chained: first 5 chars in lowercase"
        );
    }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        return resolvedRef == null ? "" : resolvedRef.toLowerCase();
    }
}
