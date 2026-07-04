package io.github.uwegeercken.bucketeer.domain.template.function;

import org.springframework.stereotype.Component;

import java.util.List;

/** {shortenedKey(ref)} → characters at index 0, 2, 4, ... of ref */
@Component
public class ShortenedKeyFunction implements TemplateFunction {

    @Override
    public String name() { return "shortenedKey"; }

    @Override
    public int expectedArgCount() { return 0; }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        if (resolvedRef == null || resolvedRef.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < resolvedRef.length(); i += 2) {
            sb.append(resolvedRef.charAt(i));
        }
        return sb.toString();
    }
}
