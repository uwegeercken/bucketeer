package io.github.uwegeercken.bucketeer.domain.template;

/**
 * Represents a single argument in a function call.
 *
 * An argument is one of:
 *   - Ref:     a reference to "key" or "pN"
 *   - Literal: a plain string value (number, pattern, offset, etc.)
 *   - Nested:  a nested function call whose result is used as the argument value
 */
public sealed interface Argument {

    /** A reference: "key" or "p1", "p2", ... "pN" */
    record Ref(String value) implements Argument {}

    /** A literal string parameter, e.g. "0", "2", "yyyy/MM/dd", "-1d" */
    record Literal(String value) implements Argument {}

    /** A nested function call, e.g. everyNth(key, 0, 2) inside upper(...) */
    record Nested(Segment.FunctionCall call) implements Argument {}
}
