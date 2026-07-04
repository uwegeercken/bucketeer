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
 *   - A FunctionCall may reference:
 *       "key"  → the user-supplied key value
 *       "pN"   → the resolved value of segment N (N must be a Literal or already resolved)
 *   - Referencing a not-yet-resolved FunctionCall segment → error
 *   - Unknown function → error
 *   - Wrong argument count → error
 */
@Component
public class TemplateResolver {

    private final Map<String, TemplateFunction> functions;

    public TemplateResolver(List<TemplateFunction> functions) {
        this.functions = functions.stream()
                .collect(Collectors.toMap(TemplateFunction::name, Function.identity()));
    }

    /**
     * Resolves the template segments using the given key.
     *
     * @param segments parsed segments from {@link TemplateParser}
     * @param key      the user-supplied key (may be null or blank)
     * @return the resolved prefix string (segments joined by '/')
     */
    public String resolve(List<Segment> segments, String key) {
        if (segments.isEmpty()) return "";

        String[] resolved = new String[segments.size()];

        // first pass: resolve all literals immediately
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) instanceof Segment.Literal lit) {
                resolved[i] = lit.value();
            }
        }

        // second pass: resolve function calls left to right
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) instanceof Segment.FunctionCall call) {
                resolved[i] = resolveFunction(call, resolved, key, segments);
            }
        }

        return String.join("/", resolved);
    }

    /**
     * Returns a list of unknown function names found in the segments.
     * Empty list means the template is valid.
     */
    public List<String> validateFunctions(List<Segment> segments) {
        return segments.stream()
                .filter(s -> s instanceof Segment.FunctionCall)
                .map(s -> ((Segment.FunctionCall) s).functionName())
                .filter(name -> !functions.containsKey(name))
                .distinct()
                .toList();
    }

    /** Returns all registered function names. */
    public List<String> availableFunctions() {
        return functions.keySet().stream().sorted().toList();
    }

    private String resolveFunction(Segment.FunctionCall call, String[] resolved,
                                   String key, List<Segment> segments) {
        TemplateFunction fn = functions.get(call.functionName());
        if (fn == null) {
            throw new TemplateParseException(
                    "Unknown function '" + call.functionName() + "' at segment " + call.position());
        }

        List<String> args = call.arguments();

        // split: first arg is the reference (key or pN), rest are literal params
        String resolvedRef = null;
        List<String> literalArgs = new ArrayList<>();

        if (!args.isEmpty()) {
            String firstArg = args.getFirst();
            if (firstArg.equals("key")) {
                resolvedRef = key != null ? key : "";
                literalArgs = args.subList(1, args.size());
            } else if (firstArg.matches("p\\d+")) {
                int refPos = Integer.parseInt(firstArg.substring(1));
                resolvedRef = resolveReference(refPos, call.position(), resolved, segments);
                literalArgs = args.subList(1, args.size());
            } else {
                // no reference argument – all args are literal (e.g. date function)
                literalArgs = args;
            }
        }

        // validate argument count (literal args only, reference excluded)
        if (literalArgs.size() != fn.expectedArgCount()
                && !(fn.name().equals("date") && literalArgs.size() == 2)) {
            throw new TemplateFunctionException(
                    "Function '" + fn.name() + "' expects " + fn.expectedArgCount()
                    + " argument(s) but got " + literalArgs.size()
                    + " at segment " + call.position());
        }

        return fn.apply(resolvedRef, literalArgs);
    }

    private String resolveReference(int refPos, int currentPos, String[] resolved, List<Segment> segments) {
        if (refPos < 1 || refPos > segments.size()) {
            throw new TemplateParseException(
                    "Reference p" + refPos + " is out of range (template has " + segments.size() + " segment(s))");
        }
        // literals are always available (resolved in first pass)
        if (segments.get(refPos - 1) instanceof Segment.Literal) {
            return resolved[refPos - 1];
        }
        // function call: must be at a lower position (already resolved)
        if (refPos >= currentPos) {
            throw new TemplateParseException(
                    "Reference p" + refPos + " at segment " + currentPos
                    + " points to a not-yet-resolved segment (p" + refPos + " is a function call at position " + refPos + ")");
        }
        if (resolved[refPos - 1] == null) {
            throw new TemplateParseException(
                    "Reference p" + refPos + " at segment " + currentPos + " could not be resolved");
        }
        return resolved[refPos - 1];
    }
}
