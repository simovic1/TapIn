package com.tapin.shared.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The schema is owned by Flyway: no profile may let Hibernate create or update tables. */
class HibernateDdlAutoConfigurationTest {

    private static final Pattern DDL_AUTO = Pattern.compile("ddl-auto:\\s*(\\S+)");

    @Test
    void noProfileEnablesHibernateSchemaGeneration() throws IOException {
        List<Path> configs;
        try (Stream<Path> files = Stream.concat(
                Files.list(Path.of("src/main/resources")), Files.list(Path.of("src/test/resources")))) {
            configs = files.filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml")).toList();
        }
        assertThat(configs).isNotEmpty();

        for (Path config : configs) {
            Matcher matcher = DDL_AUTO.matcher(Files.readString(config));
            while (matcher.find()) {
                assertThat(matcher.group(1)).as(config.toString()).isIn("validate", "none");
            }
        }
    }
}
