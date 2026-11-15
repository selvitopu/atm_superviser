package io.github.selvitopu.atmsup.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LiquibaseDemoContextTest {

    private static Object process(MockEnvironment env, Object bean) {
        LiquibaseDemoContext processor = new LiquibaseDemoContext();
        processor.setEnvironment(env);
        return processor.postProcessBeforeInitialization(bean, "liquibase");
    }

    @Test
    void demoModeActivatesTheDemoContext() {
        SpringLiquibase liquibase = new SpringLiquibase();

        process(new MockEnvironment().withProperty("atm.demo-mode", "true"), liquibase);

        assertThat(liquibase.getContexts()).isEqualTo("demo");
    }

    @Test
    void otherwiseOnlyTheDefaultContext() {
        SpringLiquibase off = new SpringLiquibase();
        SpringLiquibase absent = new SpringLiquibase();

        process(new MockEnvironment().withProperty("atm.demo-mode", "false"), off);
        process(new MockEnvironment(), absent);

        // never empty: Liquibase runs every changeset, the demo ones included, when no context is set
        assertThat(off.getContexts()).isEqualTo("default");
        assertThat(absent.getContexts()).isEqualTo("default");
    }

    @Test
    void otherBeansAreUntouched() {
        Object bean = new Object();

        assertThat(process(new MockEnvironment().withProperty("atm.demo-mode", "true"), bean)).isSameAs(bean);
    }
}
