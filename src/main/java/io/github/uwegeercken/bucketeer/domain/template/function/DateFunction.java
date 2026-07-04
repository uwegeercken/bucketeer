package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {date(pattern)}          → current date/time formatted with pattern
 * {date(pattern, offset)}  → current date/time ± offset, formatted with pattern
 *
 * Offset syntax: +1d, -2h, +3w, -1M, +2y
 *   d = days, h = hours, w = weeks, M = months, y = years
 *
 * Pattern: Java DateTimeFormatter syntax, e.g. yyyy/MM/dd, yyyyMMdd
 */
@Component
public class DateFunction implements TemplateFunction {

    private static final Pattern OFFSET_PATTERN = Pattern.compile("^([+-])(\\d+)([dhwMy])$");

    @Override
    public String name() { return "date"; }

    @Override
    public int expectedArgCount() { return 1; }  // pattern is required; offset is optional (max 2 args total)

    @Override
    public String apply(String resolvedRef, List<String> args) {
        // args: [pattern] or [pattern, offset]  — resolvedRef is unused for date
        if (args.isEmpty()) {
            throw new TemplateFunctionException("Function 'date': pattern argument is required");
        }
        String pattern = args.getFirst();
        ZonedDateTime now = ZonedDateTime.now();

        if (args.size() >= 2) {
            now = applyOffset(now, args.get(1).trim());
        }

        try {
            return DateTimeFormatter.ofPattern(pattern).format(now);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new TemplateFunctionException("Function 'date': invalid pattern '" + pattern + "': " + e.getMessage());
        }
    }

    private ZonedDateTime applyOffset(ZonedDateTime dt, String offset) {
        Matcher m = OFFSET_PATTERN.matcher(offset);
        if (!m.matches()) {
            throw new TemplateFunctionException(
                    "Function 'date': invalid offset '" + offset + "'. Expected format: +1d, -2h, +3w, -1M, +2y");
        }
        int sign   = m.group(1).equals("+") ? 1 : -1;
        long amount = Long.parseLong(m.group(2)) * sign;
        String unit = m.group(3);

        return switch (unit) {
            case "h" -> dt.plus(amount, ChronoUnit.HOURS);
            case "d" -> dt.plus(amount, ChronoUnit.DAYS);
            case "w" -> dt.plus(amount, ChronoUnit.WEEKS);
            case "M" -> dt.plusMonths(amount);
            case "y" -> dt.plusYears(amount);
            default  -> throw new TemplateFunctionException("Function 'date': unknown offset unit '" + unit + "'");
        };
    }
}
