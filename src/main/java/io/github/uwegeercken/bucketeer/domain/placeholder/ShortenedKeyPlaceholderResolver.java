package io.github.uwegeercken.bucketeer.domain.placeholder;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {shortenedKey} → characters at index 0, 2, 4, ... of the key.
 *
 * Example: MTIzLzQ1Ni83ODkvMDEy → MILQN8OkME
 */
@Component
public class ShortenedKeyPlaceholderResolver implements PlaceholderResolver {

    @Override
    public String name() {
        return "shortenedKey";
    }

    @Override
    public String resolve(String key, Map<String, String> params) {
        if (key == null || key.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i += 2) {
            sb.append(key.charAt(i));
        }
        return sb.toString();
    }
}
