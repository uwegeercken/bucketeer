package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {upper(ref)} → ref in uppercase */
@Component
public class UpperFunction implements TemplateFunction {

    @Override public String name() { return "upper"; }
    @Override public int expectedArgCount() { return 0; }

    @Override
    public String description() { return "Converts the input to uppercase."; }

    @Override
    public List<String> examples() {
        return List.of(
                "{upper(key)}                    → e.g. \"abcdef\" → \"ABCDEF\"",
                "{upper(everyNth(key, 0, 2))}    → chained: everyNth result in uppercase"
        );
    }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        return resolvedRef == null ? "" : resolvedRef.toUpperCase();
    }
}
