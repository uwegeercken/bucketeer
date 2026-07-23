package io.github.uwegeercken.bucketeer.domain.template;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Facade combining {@link TemplateParser} and {@link TemplateResolver}.
 * Entry point for all template operations.
 */
@Component
public class PrefixTemplateEngine {

    private final TemplateParser parser;
    private final TemplateResolver resolver;

    public PrefixTemplateEngine(TemplateParser parser, TemplateResolver resolver) {
        this.parser   = parser;
        this.resolver = resolver;
    }

    /** Parses and resolves the template in one step. */
    public String resolve(String template, String key, String bucket) {
        List<Segment> segments = parser.parse(template);
        return resolver.resolve(segments, key, bucket);
    }

    /** Returns unknown function names found in the template. Empty = valid. */
    public List<String> validate(String template) {
        List<Segment> segments = parser.parse(template);
        return resolver.validateFunctions(segments);
    }

    /** Returns all registered function names. */
    public List<String> availableFunctions() {
        return resolver.availableFunctions();
    }
}
