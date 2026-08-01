package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CartControllerTest {

    @Test
    @DisplayName("replaces the object's own folder with the target prefix")
    void replacesFolder() {
        assertThat(CartController.targetKey("testdata/events/shard-00/event-02992.json", "archive/"))
                .isEqualTo("archive/event-02992.json");
        assertThat(CartController.targetKey("testdata/events/shard-00/test1/futjes.odt", "archive/"))
                .isEqualTo("archive/futjes.odt");
    }

    @Test
    @DisplayName("adds a trailing slash to the target prefix")
    void targetPrefixWithoutTrailingSlash() {
        assertThat(CartController.targetKey("a/b/c.txt", "archive"))
                .isEqualTo("archive/c.txt");
    }

    @Test
    @DisplayName("empty target prefix moves to the bucket root")
    void emptyTargetPrefix() {
        assertThat(CartController.targetKey("a/b/c.txt", ""))
                .isEqualTo("c.txt");
    }

    @Test
    @DisplayName("objects at the bucket root keep their name")
    void keyWithoutFolder() {
        assertThat(CartController.targetKey("top.txt", "move1/"))
                .isEqualTo("move1/top.txt");
    }

    @Test
    @DisplayName("null target prefix behaves like an empty one")
    void nullTargetPrefix() {
        assertThat(CartController.targetKey("a/b/c.txt", null))
                .isEqualTo("c.txt");
    }
}
