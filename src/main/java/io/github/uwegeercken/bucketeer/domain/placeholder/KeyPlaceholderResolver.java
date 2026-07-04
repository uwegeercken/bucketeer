package io.github.uwegeercken.bucketeer.domain.placeholder;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {key} → the key as entered by the user, unchanged.
 */
@Component
public class KeyPlaceholderResolver implements PlaceholderResolver {

    @Override
    public String name() {
        return "key";
    }

    @Override
    public String resolve(String key, Map<String, String> params) {
        return key != null ? key : "";
    }
}
