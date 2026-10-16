package com.tapin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
class PasswordResetController {
    private final PasswordResetService resets;
    private final AuthRequestLimiter limiter;
    PasswordResetController(PasswordResetService resets, AuthRequestLimiter limiter) {
        this.resets = resets; this.limiter = limiter;
    }
    record ResetRequest(@NotBlank @Email @Size(max = 320) String email) {}
    record ConfirmRequest(@NotBlank @Size(min = 32, max = 256) String token, @NotBlank @Size(max = 128) String newPassword) {}

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void request(@Valid @RequestBody ResetRequest body, HttpServletRequest http) {
        limiter.acquire("reset-request", http.getRemoteAddr(), body.email());
        resets.request(body.email());
    }
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@Valid @RequestBody ConfirmRequest body, HttpServletRequest http) {
        limiter.acquire("reset-confirm", http.getRemoteAddr(), InvitationTokens.hash(body.token()));
        resets.confirm(body.token(), body.newPassword());
    }
}
