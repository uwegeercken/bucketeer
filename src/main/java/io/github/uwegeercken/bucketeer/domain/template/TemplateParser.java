package io.github.uwegeercken.bucketeer.domain.template;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a prefix template string into an ordered list of {@link Segment}s.
 *
 * Rules:
 *   - Segments are separated by '/'
 *   - A segment wrapped in { } is a function call: {functionName(arg1, arg2, ...)}
 *   - A segment without { } is a literal
 *   - \{ is an escaped literal '{'
 *
 * Examples:
 *   "data/{shortenedKey(key)}/{key}/"  →  [Literal("data"), FunctionCall("shortenedKey",["key"]), FunctionCall("key",[]), Literal("")]
 *   "data/{left(p3,4)}/ABCDEFGH/"     →  [Literal("data"), FunctionCall("left",["p3","4"]), Literal("ABCDEFGH"), Literal("")]
 */
@Component
public class TemplateParser {

    private static final Pattern FUNCTION_PATTERN =
            Pattern.compile("^(?<!\\\\)\\{([a-zA-Z][a-zA-Z0-9]*)(?:\\(([^)]*)\\))?}$");

    /**
     * Parses the template into segments.
     *
     * @param template the raw prefix template string
     * @return ordered list of segments (1-based positions)
     * @throws TemplateParseException if the template is syntactically invalid
     */
    public List<Segment> parse(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }

        String[] parts = template.split("/", -1);
        List<Segment> segments = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            int position = i + 1;
            String raw = parts[i];
            segments.add(parseSegment(position, raw));
        }

        return List.copyOf(segments);
    }

    private Segment parseSegment(int position, String raw) {
        // unescape \{ → treat as literal
        if (raw.startsWith("\\{")) {
            return new Segment.Literal(position, raw.substring(1));
        }

        Matcher matcher = FUNCTION_PATTERN.matcher(raw);
        if (matcher.matches()) {
            String functionName = matcher.group(1);
            String argsRaw      = matcher.group(2);
            List<String> args   = parseArguments(argsRaw);
            return new Segment.FunctionCall(position, functionName, args);
        }

        // plain literal (including empty trailing segment after final slash)
        return new Segment.Literal(position, raw);
    }

    private List<String> parseArguments(String argsRaw) {
        if (argsRaw == null || argsRaw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(argsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
