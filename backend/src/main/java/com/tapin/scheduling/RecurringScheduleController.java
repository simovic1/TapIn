package com.tapin.scheduling;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.scheduling.RecurringScheduleService.CreateCommand;
import com.tapin.scheduling.RecurringScheduleService.ScheduleView;
import com.tapin.scheduling.RecurringScheduleService.UpdateCommand;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** Endpoint access (read: any user, write: TRAINER/ADMIN) is enforced by ApiAuthorizationRules. */
@Tag(name = "Trainings")
@RestController
@RequestMapping(ApiPaths.V1 + "/recurring-schedules")
class RecurringScheduleController {

    static final String LOCAL_TIME = "^([01][0-9]|2[0-3]):[0-5][0-9]$";
    static final String LOCAL_TIME_MESSAGE = "Vrijeme mora biti u obliku HH:mm.";

    private final RecurringScheduleService service;

    RecurringScheduleController(RecurringScheduleService service) {
        this.service = service;
    }

    record RecurringScheduleListResponse(List<ScheduleView> items) {
    }

    record CreateRecurringScheduleRequest(
            @NotNull DayOfWeek weekday,
            @NotNull @Pattern(regexp = LOCAL_TIME, message = LOCAL_TIME_MESSAGE) String startTime,
            @NotNull @Pattern(regexp = LOCAL_TIME, message = LOCAL_TIME_MESSAGE) String endTime,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            Boolean enabled) {
    }

    /**
     * Absent field = unchanged; {@code "validTo": null} removes the end date. A class with a setter (not a record
     * with {@code Optional}) because Jackson maps an absent {@code Optional} record component to
     * {@code Optional.empty()}, which would be indistinguishable from an explicit null.
     */
    static final class UpdateRecurringScheduleRequest {
        @NotNull @Min(0) public Long expectedVersion;
        @Pattern(regexp = LOCAL_TIME, message = LOCAL_TIME_MESSAGE) public String startTime;
        @Pattern(regexp = LOCAL_TIME, message = LOCAL_TIME_MESSAGE) public String endTime;
        public Boolean enabled;
        private Optional<LocalDate> validTo;

        @JsonSetter("validTo")
        void setValidTo(LocalDate value) {
            validTo = Optional.ofNullable(value);
        }

        /** {@code null} = not sent, empty = sent as null. */
        Optional<LocalDate> validTo() {
            return validTo;
        }
    }

    @Operation(summary = "Pravila rasporeda")
    @GetMapping
    RecurringScheduleListResponse list() {
        return new RecurringScheduleListResponse(service.list());
    }

    @Operation(summary = "Novo pravilo rasporeda")
    @PostMapping
    ResponseEntity<ScheduleView> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                                     @Valid @RequestBody CreateRecurringScheduleRequest request) {
        ScheduleView created = service.create(actor, new CreateCommand(request.weekday(),
                LocalTime.parse(request.startTime()), LocalTime.parse(request.endTime()), request.validFrom(),
                request.validTo(), request.enabled() == null || request.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Izmjena budućeg rasporeda")
    @PatchMapping("/{scheduleId}")
    ScheduleView update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long scheduleId,
                                     @Valid @RequestBody UpdateRecurringScheduleRequest request) {
        return service.update(actor, scheduleId, new UpdateCommand(
                request.expectedVersion,
                request.startTime != null ? LocalTime.parse(request.startTime) : null,
                request.endTime != null ? LocalTime.parse(request.endTime) : null,
                request.validTo(), request.enabled));
    }
}
