package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {upper(ref)} → ref in uppercase */
@Component
public class UpperFunction implements TemplateFunction {

    @Override
    public String name() { return "upper"; }

    @Override
    public int expectedArgCount() { return 0; }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        return resolvedRef == null ? "" : resolvedRef.toUpperCase();
    }
}
