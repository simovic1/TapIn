package com.tapin.belts;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.belts.BeltService.AuditPage;
import com.tapin.belts.BeltService.BeltHistory;
import com.tapin.belts.BeltService.CorrectCommand;
import com.tapin.belts.BeltService.CreateCommand;
import com.tapin.belts.BeltService.MutationResult;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Optional;

/**
 * {@code /members/{id}/belts/**} is TRAINER/ADMIN only and {@code /me/belts} uses the session's own membership
 * (ApiAuthorizationRules), so a member can never read or change someone else's belts by changing an id (AC-28/29).
 * There is intentionally no DELETE and no stripe field (AC-31).
 */
@Tag(name = "Belts")
@RestController
class BeltController {

    private final BeltService service;

    BeltController(BeltService service) {
        this.service = service;
    }

    record CreateBeltPeriodRequest(
            @NotNull Belt belt,
            LocalDate awardedOn,
            PromotionTiming promotionTiming,
            LocalDate effectiveFromDate,
            Long awardedByUserId,
            @Size(max = 500) String note) {
    }

    /** Absent field = unchanged; {@code null} clears awardedOn, awardedByUserId or note. */
    static final class CorrectBeltPeriodRequest {
        @NotNull @Min(0) public Long expectedVersion;
        public Belt belt;
        public PromotionTiming promotionTiming;
        public LocalDate effectiveFromDate;
        @NotBlank @Size(max = 500) public String reason;
        private Optional<LocalDate> awardedOn;
        private Optional<Long> awardedByUserId;
        @Size(max = 500) private String note;
        private boolean noteSent;

        @JsonSetter("awardedOn")
        void setAwardedOn(LocalDate value) {
            awardedOn = Optional.ofNullable(value);
        }

        @JsonSetter("awardedByUserId")
        void setAwardedByUserId(Long value) {
            awardedByUserId = Optional.ofNullable(value);
        }

        @JsonSetter("note")
        void setNote(String value) {
            note = value;
            noteSent = true;
        }

        Optional<String> note() {
            return noteSent ? Optional.ofNullable(note) : null;
        }
    }

    @Operation(summary = "Vlastita povijest pojaseva i broj treninga po pojasu")
    @GetMapping(ApiPaths.V1 + "/me/belts")
    BeltHistory own(@AuthenticationPrincipal AuthenticatedUser viewer) {
        return service.history(viewer.membershipId());
    }

    @Operation(summary = "Povijest pojaseva člana i broj treninga po pojasu")
    @GetMapping(ApiPaths.V1 + "/members/{membershipId}/belts")
    BeltHistory history(@PathVariable long membershipId) {
        return service.history(membershipId);
    }

    @Operation(summary = "Nova promocija ili početni/povijesni unos pojasa")
    @PostMapping(ApiPaths.V1 + "/members/{membershipId}/belts")
    ResponseEntity<MutationResult> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                          @PathVariable long membershipId,
                                          @Valid @RequestBody CreateBeltPeriodRequest request) {
        MutationResult result = service.create(actor, membershipId, new CreateCommand(request.belt(), request.awardedOn(),
                request.promotionTiming(), request.effectiveFromDate(), request.awardedByUserId(), request.note()));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @Operation(summary = "Ispravak pojasa ili datuma uz audit")
    @PatchMapping(ApiPaths.V1 + "/members/{membershipId}/belts/{beltPeriodId}")
    MutationResult correct(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long membershipId,
                           @PathVariable long beltPeriodId, @Valid @RequestBody CorrectBeltPeriodRequest request) {
        return service.correct(actor, membershipId, beltPeriodId, new CorrectCommand(request.expectedVersion,
                request.belt, request.awardedOn, request.promotionTiming, request.effectiveFromDate,
                request.awardedByUserId, request.note(), request.reason));
    }

    @Operation(summary = "Povijest ispravaka pojaseva")
    @GetMapping(ApiPaths.V1 + "/members/{membershipId}/belts/audit")
    AuditPage audit(@PathVariable long membershipId, @RequestParam(defaultValue = "0") @Min(0) int page,
                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.audit(membershipId, page, size);
    }
}
