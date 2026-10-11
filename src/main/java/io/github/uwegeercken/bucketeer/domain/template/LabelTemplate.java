package io.github.uwegeercken.bucketeer.domain.template;

import java.util.regex.Pattern;

/**
 * A named, reusable prefix template (a "prefix variable").
 *
 * <p>Variables are defined once in the settings and referenced in the search prefix
 * field as {@code @{name}}. Before the prefix template is parsed, every reference is
 * replaced by the variable's template text, so the same rule can be reused across
 * buckets without repeating it.
 */
public record LabelTemplate(String name, String template) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

    /** Names become part of the reference syntax (@{name}); strict lowercase identifiers. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }
}