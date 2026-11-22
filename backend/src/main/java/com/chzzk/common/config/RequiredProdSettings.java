package com.chzzk.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * In the {@code prod} profile (ADR-0006) the server refuses to start unless every required setting comes from the
 * environment: the database, the MediaMTX callback secret and the public HTTPS base URL. It checks before any other
 * bean is created, so the error names the missing environment variable instead of a later connection failure.
 * The Toss keys are not checked here: they keep ADR-0004's test-key rule ({@code TossKeys}).
 */
@Component
@Profile("prod")
class RequiredProdSettings implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Property → the environment variable that sets it in {@code application-prod.yml}. */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("spring.datasource.url", "DB_URL");
        REQUIRED.put("spring.datasource.username", "DB_USERNAME");
        REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
        REQUIRED.put("broadcast.media-server.auth-secret", "MEDIAMTX_AUTH_SECRET");
        REQUIRED.put("site.public-base-url", "PUBLIC_BASE_URL");
    }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> problems = new ArrayList<>();
        REQUIRED.forEach((property, envName) -> {
            if (isBlank(valueOf(property))) problems.add(envName + " is not set");
        });
        String baseUrl = valueOf("site.public-base-url");
        if (!isBlank(baseUrl) && !baseUrl.startsWith("https://")) {
            problems.add("PUBLIC_BASE_URL must be an https:// address (got \"" + baseUrl + "\")");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The prod profile needs these environment variables (see README, prod profile): "
                    + String.join("; ", problems));
        }
    }

    /** The resolved value, or {@code null} when it is missing or refers to an unset variable. */
    private String valueOf(String property) {
        try {
            return environment.getProperty(property);
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
