package io.github.uwegeercken.bucketeer.domain.placeholder;

import java.util.Map;

/**
 * Resolves a single named placeholder in a prefix template.
 * Implementations are picked up automatically by Spring and registered in PrefixTemplateEngine.
 */
public interface PlaceholderResolver {

    /** The placeholder name, e.g. "key" matches {key} in the template. */
    String name();

    /**
     * Resolves the placeholder value.
     *
     * @param key    the key as entered by the user (may be blank)
     * @param params optional parameters extracted from the placeholder, e.g. {key:0:3} → {0="0", 1="3"}
     * @return resolved string value
     */
    String resolve(String key, Map<String, String> params);
}
