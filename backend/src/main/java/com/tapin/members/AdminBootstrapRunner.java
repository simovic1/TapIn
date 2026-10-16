package com.tapin.members;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/** Runs {@link AdminBootstrapService} once after startup (after Flyway). Disable with {@code tapin.bootstrap.admin.enabled=false}. */
@Component
@ConditionalOnBooleanProperty(name = "tapin.bootstrap.admin.enabled", matchIfMissing = true)
class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapService service;
    private final AdminBootstrapProperties properties;

    AdminBootstrapRunner(AdminBootstrapService service, AdminBootstrapProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.bootstrap(properties);
    }
}
