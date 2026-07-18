package io.github.uwegeercken.bucketeer.domain.template;

import io.github.uwegeercken.bucketeer.domain.template.function.TemplateFunction;
import io.github.uwegeercken.bucketeer.domain.template.function.TemplateFunctionException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a list of {@link Segment}s to a concrete prefix string.
 *
 * Resolution rules:
 *   - Segments are resolved left to right
 *   - Literals are always immediately available as pN references
 *   - FunctionCall arguments are resolved recursively before the function is applied:
 *       Ref("key")  → user-supplied key
 *       Ref("pN")   → resolved value of segment N
 *       Literal     → its string value
 *       Nested      → recursively resolved function call result
 *   - pN may reference any Literal segment at any position,
 *     or a FunctionCall segment at a lower position (already resolved)
 */
@Component
public class TemplateResolver {

    private final Map<String, TemplateFunction> functions;

    public TemplateResolver(List<TemplateFunction> functions) {
        this.functions = functions.stream()
                .collect(Collectors.toMap(TemplateFunction::name, Function.identity()));
    }

    public String resolve(List<Segment> segments, String key) {
        if (segments.isEmpty()) return "";

        String[] resolved = new String[segments.size()];

        // first pass: resolve all literals
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) instanceof Segment.Literal lit) {
                resolved[i] = lit.value();
            }
        }

        // second pass: resolve function calls left to right
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) instanceof Segment.FunctionCall call) {
                resolved[i] = resolveCall(call, resolved, key, segments);
            }
        }

        return String.join("/", resolved);
    }

    public List<String> validateFunctions(List<Segment> segments) {
        List<String> unknown = new ArrayList<>();
        collectUnknownFunctions(segments, unknown);
        return unknown.stream().distinct().toList();
    }

    public List<String> availableFunctions() {
        return functions.keySet().stream().sorted().toList();
    }

    // --- private ---

    private String resolveCall(Segment.FunctionCall call, String[] resolved,
                               String key, List<Segment> segments) {
        TemplateFunction fn = functions.get(call.functionName());
        if (fn == null) throw new TemplateParseException(
                "Unknown function '" + call.functionName() + "'" +
                (call.position() > 0 ? " at segment " + call.position() : ""));

        List<Argument> args = call.arguments();

        // split first argument (ref or nested) from literal params
        String resolvedRef  = null;
        List<String> params = new ArrayList<>();

        if (!args.isEmpty()) {
            Argument first = args.getFirst();
            if (first instanceof Argument.Ref ref) {
                resolvedRef = resolveRef(ref.value(), call.position(), resolved, segments, key);
                args.subList(1, args.size()).forEach(a -> params.add(resolveLiteralArg(a)));
            } else if (first instanceof Argument.Nested nested) {
                resolvedRef = resolveCall(nested.call(), resolved, key, segments);
                args.subList(1, args.size()).forEach(a -> params.add(resolveLiteralArg(a)));
            } else {
                // no ref - all args are literals (e.g. date)
                args.forEach(a -> params.add(resolveLiteralArg(a)));
            }
        }

        validateArgCount(fn, params, call);
        String result = fn.apply(resolvedRef, params);
        if (call.suffix() != null && !call.suffix().isEmpty()) {
            result = result + call.suffix();
        }
        return result;
    }

    private String resolveRef(String ref, int currentPos, String[] resolved,
                              List<Segment> segments, String key) {
        if (ref.equals("key")) return key != null ? key : "";

        int refPos = Integer.parseInt(ref.substring(1));
        if (refPos < 1 || refPos > segments.size()) throw new TemplateParseException(
                "Reference p" + refPos + " is out of range (template has " + segments.size() + " segment(s))");

        if (segments.get(refPos - 1) instanceof Segment.Literal) {
            return resolved[refPos - 1];
        }
        if (refPos >= currentPos) throw new TemplateParseException(
                "Reference p" + refPos + " at segment " + currentPos
                + " points to a not-yet-resolved segment");
        if (resolved[refPos - 1] == null) throw new TemplateParseException(
                "Reference p" + refPos + " at segment " + currentPos + " could not be resolved");

        return resolved[refPos - 1];
    }

    private String resolveLiteralArg(Argument arg) {
        return switch (arg) {
            case Argument.Literal lit -> lit.value();
            case Argument.Ref ref    -> ref.value();   // e.g. offset string "-1d" parsed as Ref
            case Argument.Nested n   -> throw new TemplateParseException(
                    "Nested function call not supported as a literal parameter");
        };
    }

    private void validateArgCount(TemplateFunction fn, List<String> params, Segment.FunctionCall call) {
        // date is special: 1 required + 1 optional arg
        if (fn.name().equals("date") && (params.size() == 1 || params.size() == 2)) return;
        if (params.size() != fn.expectedArgCount()) throw new TemplateFunctionException(
                "Function '" + fn.name() + "' expects " + fn.expectedArgCount()
                + " parameter(s) but got " + params.size()
                + (call.position() > 0 ? " at segment " + call.position() : ""));
    }

    private void collectUnknownFunctions(List<Segment> segments, List<String> unknown) {
        for (Segment seg : segments) {
            if (seg instanceof Segment.FunctionCall call) {
                if (!functions.containsKey(call.functionName())) {
                    unknown.add(call.functionName());
                }
                call.arguments().stream()
                        .filter(a -> a instanceof Argument.Nested)
                        .map(a -> ((Argument.Nested) a).call())
                        .forEach(nested -> collectUnknownFunctions(List.of(nested), unknown));
            }
        }
    }
}
