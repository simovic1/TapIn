package com.tapin.shared.error;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps {@link ErrorCode} identical to {@code components.schemas.ErrorCode} in the OpenAPI contract. */
class ErrorCodeContractTest {

    private static final Path CONTRACT = Path.of("..", "api", "openapi.yaml");

    @Test
    void errorCodesMatchOpenApiContract() throws IOException {
        List<String> contractCodes = contractErrorCodes();
        List<String> javaCodes = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();

        assertThat(javaCodes).containsExactlyInAnyOrderElementsOf(contractCodes);
    }

    @SuppressWarnings("unchecked")
    private static List<String> contractErrorCodes() throws IOException {
        try (InputStream in = Files.newInputStream(CONTRACT)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> components = (Map<String, Object>) root.get("components");
            Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
            Map<String, Object> errorCode = (Map<String, Object>) schemas.get("ErrorCode");
            return (List<String>) errorCode.get("enum");
        }
    }
}
