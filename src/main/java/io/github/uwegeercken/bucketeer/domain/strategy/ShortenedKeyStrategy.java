package io.github.uwegeercken.bucketeer.domain.strategy;

import org.springframework.stereotype.Component;

/**
 * Applies the shortened-key pattern:
 *   shortened key = characters at index 0, 2, 4, 6, ... of the full key
 *   resolved prefix = <base prefix>/<shortened key>/<full key>/
 *
 * Example:
 *   key            = MTIzLzQ1Ni83ODkvMDEy
 *   shortened key  = MILQN8OkME
 *   resolved prefix = myprefix/MILQN8OkME/MTIzLzQ1Ni83ODkvMDEy/
 */
@Component
public class ShortenedKeyStrategy implements KeyResolutionStrategy {

    @Override
    public String name() {
        return "Gekürzter Schlüssel + Schlüssel";
    }

    @Override
    public String resolvePrefix(String prefix, String key) {
        if (key == null || key.isBlank()) {
            return prefix != null ? prefix : "";
        }
        String base = (prefix != null && !prefix.isBlank())
                ? (prefix.endsWith("/") ? prefix : prefix + "/")
                : "";
        String shortened = shorten(key);
        return base + shortened + "/" + key + "/";
    }

    private String shorten(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i += 2) {
            sb.append(key.charAt(i));
        }
        return sb.toString();
    }
}
