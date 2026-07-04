package io.github.uwegeercken.bucketeer.domain.template.function;

import java.util.List;

/**
 * A named function usable in a prefix template placeholder.
 *
 * Implementations are Spring beans and are auto-discovered by {@link io.github.uwegeercken.bucketeer.domain.template.TemplateResolver}.
 *
 * Argument convention:
 *   - The first argument is always the resolved reference value (from "key" or "pN")
 *     for functions that operate on a string reference.
 *   - Additional arguments follow as plain strings (numbers, patterns, offsets, etc.)
 *   - Functions without a string reference (e.g. date) receive only their own arguments.
 */
public interface TemplateFunction {

    /** Function name as used in the template, e.g. "shortenedKey", "left", "date". */
    String name();

    /** Expected number of arguments (excluding the resolved reference value). */
    int expectedArgCount();

    /**
     * Applies the function.
     *
     * @param resolvedRef the resolved value of the reference argument ("key" or "pN"), or null if not applicable
     * @param args        additional literal arguments from the template
     * @return the computed string value for this segment
     * @throws TemplateFunctionException if arguments are invalid
     */
    String apply(String resolvedRef, List<String> args);
}
