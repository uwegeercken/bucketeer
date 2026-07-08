package io.github.uwegeercken.bucketeer.domain.template;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a prefix template string into an ordered list of {@link Segment}s.
 *
 * Syntax:
 *   - Segments are separated by '/'
 *   - A segment wrapped in { } is a function call: {functionName(arg1, arg2, ...)}
 *   - Arguments can be: "key", "pN", a literal value, or a nested function call
 *   - Nested calls: {upper(everyNth(key, 0, 2))}
 *   - \{ is an escaped literal '{'
 */
@Component
public class TemplateParser {

    private static final Pattern FUNCTION_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*");
    private static final Pattern REF_PATTERN   = Pattern.compile("key|p\\d+");

    /**
     * Parses the template into segments.
     *
     * @param template the raw prefix template string
     * @return ordered list of segments (1-based positions)
     */
    public List<Segment> parse(String template) {
        if (template == null || template.isBlank()) return List.of();

        List<String> parts = splitSegments(template);
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            segments.add(parseSegment(i + 1, parts.get(i)));
        }
        return List.copyOf(segments);
    }

    /**
     * Splits the template at '/' characters that are outside of { } braces.
     * Slashes inside placeholders (e.g. date patterns like yyyy/MM/dd) are not treated as separators.
     */
    private List<String> splitSegments(String template) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '/' && depth == 0) {
                parts.add(template.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(template.substring(start));
        return parts;
    }

    private Segment parseSegment(int position, String raw) {
        if (raw.startsWith("\\{")) {
            return new Segment.Literal(position, raw.substring(1));
        }
        if (raw.startsWith("{") && raw.endsWith("}")) {
            String inner = raw.substring(1, raw.length() - 1).trim();
            return new Segment.FunctionCall(position, parseFunctionName(inner),
                    parseArguments(parseArgumentString(inner)));
        }
        return new Segment.Literal(position, raw);
    }

    /** Extracts the function name from "funcName(args)" or "funcName" */
    private String parseFunctionName(String inner) {
        int paren = inner.indexOf('(');
        return paren < 0 ? inner.trim() : inner.substring(0, paren).trim();
    }

    /** Extracts the raw argument string from "funcName(args)" */
    private String parseArgumentString(String inner) {
        int open  = inner.indexOf('(');
        int close = inner.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return "";
        return inner.substring(open + 1, close).trim();
    }

    /** Splits a raw argument string into tokens, respecting nested parentheses */
    private List<Argument> parseArguments(String argsRaw) {
        if (argsRaw.isBlank()) return List.of();

        List<String> tokens = splitRespectingParentheses(argsRaw);
        List<Argument> result = new ArrayList<>();
        for (String token : tokens) {
            result.add(parseArgument(token.trim()));
        }
        return List.copyOf(result);
    }

    /** Parses a single argument token into a Ref, Literal, or Nested */
    private Argument parseArgument(String token) {
        // nested function call: contains parentheses
        int paren = token.indexOf('(');
        if (paren > 0 && token.endsWith(")")) {
            String name    = token.substring(0, paren).trim();
            String argsPart = token.substring(paren + 1, token.length() - 1).trim();
            if (FUNCTION_NAME.matcher(name).matches()) {
                List<Argument> nestedArgs = parseArguments(argsPart);
                Segment.FunctionCall nested = new Segment.FunctionCall(-1, name, nestedArgs);
                return new Argument.Nested(nested);
            }
        }
        // reference: "key" or "pN"
        if (REF_PATTERN.matcher(token).matches()) {
            return new Argument.Ref(token);
        }
        // literal: anything else (number, pattern, offset, plain string)
        return new Argument.Literal(token);
    }

    /**
     * Splits a comma-separated argument string while respecting nested parentheses.
     * e.g. "everyNth(key, 0, 2), 4" → ["everyNth(key, 0, 2)", "4"]
     */
    private List<String> splitRespectingParentheses(String input) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                tokens.add(input.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = input.substring(start).trim();
        if (!last.isEmpty()) tokens.add(last);
        return tokens;
    }
}
