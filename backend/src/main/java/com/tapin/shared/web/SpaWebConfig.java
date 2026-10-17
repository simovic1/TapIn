package com.tapin.shared.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The Render image bundles the public SPA shell. API data keeps its normal authorization rules. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "tapin.frontend.enabled", havingValue = "true")
public class SpaWebConfig implements WebMvcConfigurer {
    public static final String[] PAGE_PATHS = {
        "/", "/prijava", "/pozivnica", "/zaboravljena-lozinka", "/nova-lozinka",
        "/profil", "/treninzi", "/treninzi/**", "/clanovi", "/clanovi/**",
        "/administracija", "/prozivka", "/prozivka/**", "/statistika", "/izvjestaji"
    };
    public static final String[] ASSET_PATHS = {
        "/index.html", "/assets/**", "/manifest.webmanifest", "/sw.js", "/offline.html",
        "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png",
        "/icon-192.png", "/icon-512.png", "/icon-maskable-512.png"
    };

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : PAGE_PATHS) registry.addViewController(path).setViewName("forward:/index.html");
    }
}
