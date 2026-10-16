package com.tapin.attendance;

import com.tapin.attendance.AttendanceService.AttendanceResult;
import com.tapin.attendance.AttendanceService.AuditPage;
import com.tapin.attendance.AttendanceService.BulkResult;
import com.tapin.attendance.AttendanceService.Roster;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** TRAINER/ADMIN only (ApiAuthorizationRules); any trainer may record any session, assigned or not. */
@Tag(name = "Attendance")
@RestController
@RequestMapping(ApiPaths.V1 + "/trainings/{trainingId}")
class AttendanceController {

    private final AttendanceService service;

    AttendanceController(AttendanceService service) {
        this.service = service;
    }

    record SetAttendanceRequest(
            @NotNull AttendanceStatus status,
            @NotNull @Min(0) Long expectedVersion,
            @Size(max = 500) String note,
            AttendanceSource source) {
    }

    @Operation(summary = "Prozivka – prijavljeni, evidentirani i brojači")
    @GetMapping("/roster")
    Roster roster(@AuthenticationPrincipal AuthenticatedUser viewer, @PathVariable long trainingId) {
        return service.roster(viewer, trainingId);
    }

    @Operation(summary = "Postavi PRESENT ili ABSENT")
    @PutMapping("/attendance/{membershipId}")
    AttendanceResult set(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long trainingId,
                         @PathVariable long membershipId, @Valid @RequestBody SetAttendanceRequest request) {
        return service.set(actor, trainingId, membershipId, request.status(), request.expectedVersion(),
                request.note(), request.source() != null ? request.source() : AttendanceSource.SINGLE);
    }

    @Operation(summary = "Povratak na UNMARKED (iznimni slučaj)")
    @DeleteMapping("/attendance/{membershipId}")
    AttendanceResult reset(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long trainingId,
                           @PathVariable long membershipId, @RequestParam @Min(0) long expectedVersion,
                           @RequestParam(required = false) @Size(max = 500) String note) {
        return service.reset(actor, trainingId, membershipId, expectedVersion, note);
    }

    record BulkAbsentRequest(Set<Long> expectedMembershipIds, @Size(max = 500) String note) {
    }

    @Operation(summary = "Označi sve preostale (REGISTERED + UNMARKED) odsutnima")
    @PostMapping("/attendance/mark-unconfirmed-absent")
    BulkResult markUnconfirmedAbsent(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long trainingId,
                                     @Valid @RequestBody(required = false) BulkAbsentRequest request) {
        return service.markUnconfirmedAbsent(actor, trainingId, request != null ? request.expectedMembershipIds() : null,
                request != null ? request.note() : null);
    }

    @Operation(summary = "Audit prisutnosti za trening (append-only)")
    @GetMapping("/attendance/audit")
    AuditPage audit(@AuthenticationPrincipal AuthenticatedUser viewer, @PathVariable long trainingId,
                    @RequestParam(required = false) Long membershipId,
                    @RequestParam(defaultValue = "0") @Min(0) int page,
                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.audit(viewer, trainingId, membershipId, page, size);
    }
}
