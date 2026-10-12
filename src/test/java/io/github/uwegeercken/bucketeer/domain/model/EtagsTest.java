package io.github.uwegeercken.bucketeer.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EtagsTest {

    @Test
    @DisplayName("stripQuotes removes the surrounding quotes S3 adds to an ETag header value")
    void stripsSurroundingQuotes() {
        assertThat(Etags.stripQuotes("\"74c269b9802a046bce5e05c966d31827\""))
                .isEqualTo("74c269b9802a046bce5e05c966d31827");
    }

    @Test
    @DisplayName("stripQuotes leaves an already unquoted ETag unchanged")
    void keepsUnquotedValue() {
        assertThat(Etags.stripQuotes("abc123")).isEqualTo("abc123");
    }

    @Test
    @DisplayName("stripQuotes never invents an empty value for null or a lone quote")
    void handlesNullAndEdgeCases() {
        assertThat(Etags.stripQuotes(null)).isNull();
        assertThat(Etags.stripQuotes("\"")).isEqualTo("\"");
        assertThat(Etags.stripQuotes("")).isEqualTo("");
    }
}
