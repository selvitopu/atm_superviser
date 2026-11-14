package io.github.selvitopu.atmsup.supervisor;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(SupervisorApplication.class);

    @Test
    void modulesVerify() {
        MODULES.verify();
    }

    /** Writes the module canvases and diagrams to the Documenter's default, target/spring-modulith-docs. */
    @Test
    void documentationIsWritten() {
        new Documenter(MODULES).writeDocumentation();
    }
}
