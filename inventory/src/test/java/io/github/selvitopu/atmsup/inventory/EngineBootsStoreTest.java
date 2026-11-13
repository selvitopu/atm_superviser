package io.github.selvitopu.atmsup.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineBootsStoreTest {

    @TempDir
    Path dir;

    @Test
    void bootsIncrementAndPersistAcrossInstances() {
        Path file = dir.resolve("boots.json");
        assertThat(new EngineBootsStore(file).nextBoots("RAD-N")).isEqualTo(1);
        assertThat(new EngineBootsStore(file).nextBoots("RAD-N")).isEqualTo(2);
        assertThat(new EngineBootsStore(file).nextBoots("SDPS-A")).isEqualTo(1);
    }

    @Test
    void createsMissingParentDirectories() {
        Path file = dir.resolve("nested/deeper/boots.json");
        assertThat(new EngineBootsStore(file).nextBoots("RAD-N")).isEqualTo(1);
        assertThat(file).exists();
    }

    @Test
    void corruptFileFailsFastWithPath() throws IOException {
        Path file = dir.resolve("boots.json");
        Files.writeString(file, "{nope");
        assertThatThrownBy(() -> new EngineBootsStore(file).nextBoots("X"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }
}
