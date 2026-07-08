package io.github.uwegeercken.bucketeer.domain.template;

import java.util.List;

/**
 * Represents a single segment of a prefix template (between slashes).
 *
 * A segment is either:
 *   - LITERAL  : a plain string, e.g. "myprefix" or "ABCDEFGH"
 *   - FUNCTION : a placeholder with a function call, e.g. {upper(everyNth(key, 0, 2))}
 */
public sealed interface Segment {

    /** 1-based position of this segment in the template. */
    int position();

    record Literal(int position, String value) implements Segment {}

    record FunctionCall(
            int position,
            String functionName,
            List<Argument> arguments
    ) implements Segment {}
}
