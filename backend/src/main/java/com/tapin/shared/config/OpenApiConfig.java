package com.tapin.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI tapInOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("TapIn API")
                .description("Evidencija BJJ treninga - REST API v1")
                .version(build != null ? build.getVersion() : "dev"));
    }
}
