package com.tapin.notifications;

import com.tapin.notifications.OutboxOperations.Overview;
import com.tapin.notifications.OutboxOperations.RetryResult;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** {@code /admin/outbox/**} is ADMIN only (ApiAuthorizationRules). Operational API, no screen (BJJ-30). */
@Tag(name = "Operations")
@RestController
class OutboxAdminController {

    private final OutboxOperations operations;

    OutboxAdminController(OutboxOperations operations) {
        this.operations = operations;
    }

    @Operation(summary = "Stanje reda e-mail poruka, upozorenje i neuspjele poruke")
    @GetMapping(ApiPaths.V1 + "/admin/outbox")
    Overview overview(@RequestParam Optional<OutboxStatus> status,
                      @RequestParam(defaultValue = "0") @Min(0) int page,
                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return operations.overview(status, page, size);
    }

    @Operation(summary = "Ponovno slanje neuspjele poruke")
    @PostMapping(ApiPaths.V1 + "/admin/outbox/{outboxId}/retry")
    RetryResult retry(@PathVariable long outboxId) {
        return operations.retry(outboxId);
    }
}
