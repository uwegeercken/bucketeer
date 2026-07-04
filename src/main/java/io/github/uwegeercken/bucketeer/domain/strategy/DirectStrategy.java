package io.github.uwegeercken.bucketeer.domain.strategy;

import org.springframework.stereotype.Component;

/**
 * No transformation applied. Lists objects directly under the given prefix.
 * If a key is provided it is appended to the prefix as-is.
 */
@Component
public class DirectStrategy implements KeyResolutionStrategy {

    @Override
    public String name() {
        return "Direkt";
    }

    @Override
    public String resolvePrefix(String prefix, String key) {
        String base = prefix != null ? prefix : "";
        if (key == null || key.isBlank()) {
            return base;
        }
        return base + key;
    }
}
