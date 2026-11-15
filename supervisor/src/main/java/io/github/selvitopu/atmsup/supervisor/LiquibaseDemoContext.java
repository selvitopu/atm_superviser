package io.github.selvitopu.atmsup.supervisor;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Selects the Liquibase context from {@code atm.demo-mode}: {@code demo} loads the demo accounts, exactly when the
 * built-in demo SNMP passwords are allowed too. Otherwise the context is {@code default}, never empty: with no
 * context, Liquibase would run every changeset, the demo ones included.
 */
@Component
public final class LiquibaseDemoContext implements BeanPostProcessor, EnvironmentAware {

    static final String DEMO = "demo";
    static final String DEFAULT = "default";

    private boolean demoMode;

    @Override
    public void setEnvironment(Environment environment) {
        demoMode = environment.getProperty("atm.demo-mode", Boolean.class, false);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof SpringLiquibase liquibase) {
            liquibase.setContexts(demoMode ? DEMO : DEFAULT);
        }
        return bean;
    }
}
