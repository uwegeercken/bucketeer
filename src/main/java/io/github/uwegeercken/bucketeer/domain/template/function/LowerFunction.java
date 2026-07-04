package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {lower(ref)} → ref in lowercase */
@Component
public class LowerFunction implements TemplateFunction {

    @Override
    public String name() { return "lower"; }

    @Override
    public int expectedArgCount() { return 0; }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        return resolvedRef == null ? "" : resolvedRef.toLowerCase();
    }
}
