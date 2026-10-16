package com.tapin.shared.web;

import com.tapin.shared.api.ApiPaths;
import com.tapin.shared.config.ClubProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/** Public, unauthenticated metadata used by the frontend shell (and as a cheap connectivity check). */
@Tag(name = "System")
@RestController
@RequestMapping(ApiPaths.V1 + "/system")
public class SystemController {

    private final ClubProperties club;
    private final Clock clock;
    private final String version;

    public SystemController(ClubProperties club, Clock clock, ObjectProvider<BuildProperties> buildProperties) {
        this.club = club;
        this.clock = clock;
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    @Operation(summary = "Osnovni podaci o aplikaciji i klubu")
    @GetMapping("/info")
    public SystemInfo info() {
        return new SystemInfo("TapIn", version, club.name(), club.timeZone().getId(), Instant.now(clock));
    }

    public record SystemInfo(String application, String version, String clubName, String timeZone,
                             Instant serverTime) {
    }
}
