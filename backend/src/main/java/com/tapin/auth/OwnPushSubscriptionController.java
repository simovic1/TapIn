package com.tapin.auth;

import com.tapin.notifications.PushSubscriptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * This browser's web push subscription of the signed-in user (optional channel for training reminders). The table
 * belongs to {@code notifications}; {@code /me} lives in auth, so the controller only delegates.
 */
@RestController
@RequestMapping("/api/v1/me/push-subscription")
class OwnPushSubscriptionController {

    record Keys(@NotBlank @Size(max = 128) String p256dh, @NotBlank @Size(max = 64) String auth) {
    }

    /** PushSubscription.toJSON() from the browser; {@code expirationTime} is accepted and ignored. */
    record SubscriptionRequest(@NotBlank @Size(max = 2048) String endpoint, Long expirationTime, @NotNull @Valid Keys keys) {
    }

    record EndpointRequest(@NotBlank @Size(max = 2048) String endpoint) {
    }

    private final PushSubscriptions subscriptions;

    OwnPushSubscriptionController(PushSubscriptions subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PutMapping
    ResponseEntity<Void> save(@AuthenticationPrincipal AuthenticatedUser actor,
                              @Valid @RequestBody SubscriptionRequest request,
                              @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        subscriptions.register(actor.userId(), request.endpoint(), request.keys().p256dh(), request.keys().auth(),
                userAgent);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    ResponseEntity<Void> remove(@AuthenticationPrincipal AuthenticatedUser actor,
                                @Valid @RequestBody EndpointRequest request) {
        subscriptions.remove(actor.userId(), request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
