package io.github.uwegeercken.bucketeer.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedRunnerTest {

    @Test
    @DisplayName("generates deterministic keys across shard prefixes")
    void keyGeneration() {
        assertThat(SeedRunner.keyFor(0, 20)).isEqualTo("events/shard-00/event-000000.json");
        assertThat(SeedRunner.keyFor(19, 20)).isEqualTo("events/shard-19/event-000019.json");
        assertThat(SeedRunner.keyFor(20, 20)).isEqualTo("events/shard-00/event-000020.json");
        assertThat(SeedRunner.keyFor(2999, 20)).isEqualTo("events/shard-19/event-002999.json");
        assertThat(SeedRunner.keyFor(5, 1)).isEqualTo("events/shard-00/event-000005.json");
    }

    @Test
    @DisplayName("parses command line options with defaults")
    void optionParsing() {
        SeedRunner.Options o = SeedRunner.Options.parse(new String[]{
                "--bucket=data", "--count=100", "--prefixes=5", "--no-verify-ssl", "--empty"
        });
        assertThat(o).isNotNull();
        assertThat(o.bucket).isEqualTo("data");
        assertThat(o.count).isEqualTo(100);
        assertThat(o.prefixes).isEqualTo(5);
        assertThat(o.noVerifySsl).isTrue();
        assertThat(o.empty).isTrue();
        assertThat(o.endpoint).isEqualTo("http://localhost:9000");
        assertThat(o.accessKey).isEqualTo("admin");
        assertThat(o.sizeMin).isEqualTo(1024);
        assertThat(o.sizeMax).isEqualTo(10240);
        assertThat(o.parallel).isEqualTo(10);
        assertThat(o.dryRun).isFalse();
    }

    @Test
    @DisplayName("rejects invalid or unknown arguments")
    void invalidOptions() {
        assertThat(SeedRunner.Options.parse(new String[]{"--count=abc"})).isNull();
        assertThat(SeedRunner.Options.parse(new String[]{"--unknown"})).isNull();
        assertThat(SeedRunner.Options.parse(new String[]{"--count=10", "garbage"})).isNull();
        assertThat(SeedRunner.Options.parse(new String[]{"--prefixes=0"})).isNull();
        assertThat(SeedRunner.Options.parse(new String[]{"--size-min=100", "--size-max=10"})).isNull();
    }

    @Test
    @DisplayName("formats byte sizes")
    void byteFormatting() {
        assertThat(SeedRunner.formatBytes(512)).isEqualTo("512 B");
        assertThat(SeedRunner.formatBytes(2048)).isEqualTo("2.00 KB");
        assertThat(SeedRunner.formatBytes(5 * 1024 * 1024L)).isEqualTo("5.00 MB");
        assertThat(SeedRunner.formatBytes(3L * 1024 * 1024 * 1024)).isEqualTo("3.00 GB");
    }
}
