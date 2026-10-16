package com.tapin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TapInApplication {

    public static void main(String[] args) {
        SpringApplication.run(TapInApplication.class, args);
    }
}
