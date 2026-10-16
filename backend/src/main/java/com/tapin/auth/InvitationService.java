package com.tapin.auth;

import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * One-time invitations (spec §3.2). Public API for the members module (issuing) and used by
 * {@link InvitationController} (preview, accept).
 * <ul>
 *   <li>Issuing revokes the user's open invitation, so after a re-send only the newest link works.</li>
 *   <li>A link is valid until accepted, revoked or expired ({@code tapin.invitation.validity}, 7 days).</li>
 *   <li>Every invalid case answers the same {@code 422 INVITATION_INVALID_OR_EXPIRED} (no hint which one).</li>
 * </ul>
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
    static final String INVALID = "Pozivnica nije valjana ili je istekla. Zatražite novu pozivnicu od trenera.";

    private final InvitationRepository invitations;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TapInUserDetailsService userDetails;
    private final InvitationProperties properties;
    private final Clock clock;

    InvitationService(InvitationRepository invitations, UserAccountRepository users, PasswordEncoder passwordEncoder,
                      TapInUserDetailsService userDetails, InvitationProperties properties, Clock clock) {
        this.invitations = invitations;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.userDetails = userDetails;
        this.properties = properties;
        this.clock = clock;
    }

    /** The raw token exists only in this result (for the e-mail link); the database keeps its hash. */
    public record IssuedInvitation(long invitationId, String token, Instant expiresAt) {
    }

    /**
     * Issues a new invitation for an INVITED user inside the caller's transaction, revoking any open one first
     * (re-send semantics).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedInvitation issue(long clubId, long userId, Long inviterUserId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Korisnik ne postoji."));
        if (user.getAccountStatus() != AccountStatus.INVITED) {
            throw ApiException.businessRule("Račun je već aktiviran; pozivnica nije potrebna.");
        }
        Instant now = clock.instant();
        int revoked = invitations.revokeOpen(userId, now);
        String token = InvitationTokens.generate();
        Invitation invitation = invitations.save(new Invitation(clubId, userId, user.getEmail(),
                InvitationTokens.hash(token), inviterUserId, now, now.plus(properties.validity())));
        log.info("Invitation id={} issued for user id={} (revoked {} previous)", invitation.getId(), userId, revoked);
        return new IssuedInvitation(invitation.getId(), token, invitation.getExpiresAt());
    }

    public record Preview(long userId, String email, String firstName, String lastName, Instant expiresAt) {
    }

    @Transactional(readOnly = true)
    public Preview preview(String token) {
        Invitation invitation = openInvitation(token);
        UserAccount user = invitedUser(invitation);
        return new Preview(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                invitation.getExpiresAt());
    }

    public record Activation(String token, String password, String firstName, String lastName, String phone) {
    }

    /**
     * Consumes the invitation exactly once (atomic conditional update), sets the password and activates the account.
     *
     * @return the principal for the new session
     */
    @Transactional
    public AuthenticatedUser accept(Activation activation) {
        PasswordPolicy.validate(activation.password());
        Invitation invitation = openInvitation(activation.token());
        if (invitations.consume(invitation.getId(), clock.instant()) != 1) {
            throw invalid(); // accepted or revoked concurrently
        }
        UserAccount user = invitedUser(invitation);
        user.activate(passwordEncoder.encode(activation.password()), activation.firstName().strip(),
                activation.lastName().strip(), blankToNull(activation.phone()));
        users.flush();
        log.info("Invitation id={} accepted, user id={} activated", invitation.getId(), user.getId());
        return userDetails.reload(user.getId()).orElseThrow(InvitationService::invalid);
    }

    private Invitation openInvitation(String token) {
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        return invitations.findByTokenHash(InvitationTokens.hash(token))
                .filter(i -> i.isOpenAt(clock.instant()))
                .orElseThrow(InvitationService::invalid);
    }

    private UserAccount invitedUser(Invitation invitation) {
        return users.findById(invitation.getUserId())
                .filter(u -> u.getAccountStatus() == AccountStatus.INVITED)
                .orElseThrow(InvitationService::invalid);
    }

    static ApiException invalid() {
        return new ApiException(ErrorCode.INVITATION_INVALID_OR_EXPIRED, INVALID);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
