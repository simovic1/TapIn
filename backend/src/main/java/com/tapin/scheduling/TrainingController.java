package com.tapin.scheduling;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.scheduling.TrainingService.CreateCommand;
import com.tapin.scheduling.TrainingService.MutationResult;
import com.tapin.scheduling.TrainingService.TrainingView;
import com.tapin.scheduling.TrainingService.UpdateCommand;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Endpoint access (read: any user, write: TRAINER/ADMIN) is enforced by ApiAuthorizationRules. */
@Tag(name = "Trainings")
@RestController
@RequestMapping(ApiPaths.V1 + "/trainings")
class TrainingController {

    private final TrainingService service;

    TrainingController(TrainingService service) {
        this.service = service;
    }

    record TrainingListResponse(List<TrainingView> items) {
    }

    record CreateTrainingRequest(
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @Size(max = 150) String title,
            Set<Long> coachMembershipIds,
            Boolean historical,
            @Size(max = 500) String note) {
    }

    /**
     * Absent field = unchanged; {@code null} title/note clears it. Setters record which nullable fields were sent
     * (an absent {@code Optional} record component would arrive as {@code Optional.empty()}, i.e. "clear").
     */
    static final class UpdateTrainingRequest {
        @NotNull @Min(0) public Long expectedVersion;
        public Instant startAt;
        public Instant endAt;
        public Set<Long> coachMembershipIds;
        @Size(max = 150) private String title;
        @Size(max = 500) private String note;
        private boolean titleSent;
        private boolean noteSent;

        @JsonSetter("title")
        void setTitle(String value) {
            title = value;
            titleSent = true;
        }

        @JsonSetter("note")
        void setNote(String value) {
            note = value;
            noteSent = true;
        }

        Optional<String> title() {
            return titleSent ? Optional.ofNullable(title) : null;
        }

        Optional<String> note() {
            return noteSent ? Optional.ofNullable(note) : null;
        }
    }

    record CancelTrainingRequest(@NotNull @Min(0) Long expectedVersion, @Size(max = 500) String reason) {
    }

    @Operation(summary = "Vidljivi termini u razdoblju")
    @GetMapping
    TrainingListResponse list(@AuthenticationPrincipal AuthenticatedUser viewer,
                              @RequestParam(required = false) Instant from,
                              @RequestParam(required = false) Instant to,
                              @RequestParam(required = false) Set<TrainingStatus> status) {
        return new TrainingListResponse(service.list(viewer, from, to, status));
    }

    record CoachListResponse(List<TrainingService.CoachView> items) {
    }

    @Operation(summary = "Treneri koje je moguće dodijeliti terminu")
    @GetMapping("/coaches")
    CoachListResponse coaches() {
        return new CoachListResponse(service.coachCandidates());
    }

    @Operation(summary = "Detalji treninga")
    @GetMapping("/{trainingId}")
    TrainingView get(@AuthenticationPrincipal AuthenticatedUser viewer, @PathVariable long trainingId) {
        return service.get(viewer, trainingId);
    }

    @Operation(summary = "Izvanredni ili povijesni trening")
    @PostMapping
    ResponseEntity<TrainingView> create(@AuthenticationPrincipal AuthenticatedUser actor,
                                        @Valid @RequestBody CreateTrainingRequest request) {
        MutationResult result = service.create(actor, new CreateCommand(request.startAt(), request.endAt(),
                request.title(), request.coachMembershipIds(), Boolean.TRUE.equals(request.historical()),
                request.note()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/trainings/" + result.training().trainingId()))
                .body(result.training());
    }

    @Operation(summary = "Pomicanje ili izmjena pojedinačnog termina")
    @PatchMapping("/{trainingId}")
    MutationResult update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long trainingId,
                          @Valid @RequestBody UpdateTrainingRequest request) {
        return service.update(actor, trainingId, new UpdateCommand(request.expectedVersion, request.startAt,
                request.endAt, request.title(), request.coachMembershipIds, request.note()));
    }

    @Operation(summary = "Otkazivanje termina")
    @PostMapping("/{trainingId}/cancel")
    MutationResult cancel(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long trainingId,
                          @Valid @RequestBody CancelTrainingRequest request) {
        return service.cancel(actor, trainingId, request.expectedVersion(), request.reason());
    }
}
