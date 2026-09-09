package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {replace(ref, find, replacement)} → replaces ALL literal occurrences of find
 * with replacement. The search string is treated literally (no regex), like in an editor.
 */
@Component
public class ReplaceFunction implements TemplateFunction {

    @Override public String name() { return "replace"; }
    @Override public int expectedArgCount() { return 2; }

    @Override
    public String description() {
        return "Replaces all literal occurrences of a search string with a replacement. " +
               "Both strings are treated literally (no regular expressions), so dots and slashes " +
               "match themselves. Useful for normalizing keys, e.g. replacing separators or removing a suffix. " +
               "An empty replacement deletes the search string.";
    }

    @Override
    public List<String> examples() {
        return List.of(
                "{replace(key, -, _)}      → \"2024-06-07\" → \"2024_06_07\"",
                "{replace(p2, .json, )}    → remove a suffix, e.g. \"report.json\" → \"report\"",
                "{replace(key, version, v)}→ \"app-version-1\" → \"app-v-1\""
        );
    }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        if (resolvedRef == null || resolvedRef.isBlank()) return "";

        String find = args.get(0);
        if (find.isEmpty()) {
            throw new TemplateFunctionException("Function 'replace': find string must not be empty");
        }

        return resolvedRef.replace(find, args.get(1));
    }
}