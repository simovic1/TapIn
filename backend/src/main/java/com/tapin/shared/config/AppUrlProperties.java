package com.tapin.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Public URL of the web app ({@code tapin.app.base-url}, env {@code APP_BASE_URL}), used for links in e-mails.
 * Must be the HTTPS origin users open in production.
 */
@Validated
@ConfigurationProperties(prefix = "tapin.app")
public record AppUrlProperties(@NotBlank String baseUrl) {

    /** Base URL without trailing slash + path. */
    public String link(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }
}
