package com.tapin.reporting;

import com.tapin.auth.AuthenticatedUser;
import com.tapin.reporting.MemberStatisticsService.HistoryPage;
import com.tapin.reporting.MemberStatisticsService.MemberStatistics;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * {@code /me/...} always uses the session's own membershipId, so a member can only ever get their own numbers
 * (AC-17); {@code /members/{id}/...} is TRAINER/ADMIN only (ApiAuthorizationRules).
 */
@Tag(name = "Statistics")
@RestController
class StatisticsController {

    private final MemberStatisticsService service;

    StatisticsController(MemberStatisticsService service) {
        this.service = service;
    }

    @Operation(summary = "Vlastita statistika, uključujući treninge po pojasu")
    @GetMapping(ApiPaths.V1 + "/me/statistics")
    MemberStatistics own(@AuthenticationPrincipal AuthenticatedUser viewer) {
        return service.statistics(viewer.membershipId());
    }

    @Operation(summary = "Vlastita povijest treninga (datum, prijava, prisutnost)")
    @GetMapping(ApiPaths.V1 + "/me/attendance")
    HistoryPage ownHistory(@AuthenticationPrincipal AuthenticatedUser viewer,
                           @RequestParam(required = false) LocalDate from,
                           @RequestParam(required = false) LocalDate to,
                           @RequestParam(defaultValue = "0") @Min(0) int page,
                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.history(viewer.membershipId(), from, to, page, size);
    }

    @Operation(summary = "Statistika člana")
    @GetMapping(ApiPaths.V1 + "/members/{membershipId}/statistics")
    MemberStatistics member(@PathVariable long membershipId) {
        return service.statistics(membershipId);
    }

    @Operation(summary = "Povijest treninga člana")
    @GetMapping(ApiPaths.V1 + "/members/{membershipId}/attendance")
    HistoryPage memberHistory(@PathVariable long membershipId,
                              @RequestParam(required = false) LocalDate from,
                              @RequestParam(required = false) LocalDate to,
                              @RequestParam(defaultValue = "0") @Min(0) int page,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.history(membershipId, from, to, page, size);
    }
}
