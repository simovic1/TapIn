package com.tapin.members;

import com.tapin.auth.AccountStatus;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.auth.InvitationService;
import com.tapin.auth.InvitationService.IssuedInvitation;
import com.tapin.auth.UserAccountService;
import com.tapin.auth.UserAccountService.UserProfile;
import com.tapin.auth.UserAccountService.UserRef;
import com.tapin.notifications.EmailEventType;
import com.tapin.notifications.EnqueueResult;
import com.tapin.notifications.NotificationOutbox;
import com.tapin.notifications.OutboxMessage;
import com.tapin.shared.config.AppUrlProperties;
import com.tapin.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inviting members (spec §3.2, AC-01, AC-18). One transaction creates the INVITED account, the club membership
 * with its roles, the invitation and the outbox e-mail: either everything exists or nothing does.
 * <p>
 * Account status (INVITED -> ACTIVE on acceptance) is independent of membership status (ACTIVE/INACTIVE).
 */
@Service
public class MemberInvitationService {

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("d. M. yyyy. 'u' HH:mm");

    private final RoleAssignmentPolicy rolePolicy;
    private final UserAccountService userAccounts;
    private final InvitationService invitations;
    private final ClubRepository clubs;
    private final ClubMembershipRepository memberships;
    private final MembershipStatusEventRepository statusEvents;
    private final NotificationOutbox outbox;
    private final AppUrlProperties appUrl;
    private final Clock clock;

    MemberInvitationService(RoleAssignmentPolicy rolePolicy, UserAccountService userAccounts,
                            InvitationService invitations, ClubRepository clubs,
                            ClubMembershipRepository memberships, MembershipStatusEventRepository statusEvents,
                            NotificationOutbox outbox, AppUrlProperties appUrl, Clock clock) {
        this.rolePolicy = rolePolicy;
        this.userAccounts = userAccounts;
        this.invitations = invitations;
        this.clubs = clubs;
        this.memberships = memberships;
        this.statusEvents = statusEvents;
        this.outbox = outbox;
        this.appUrl = appUrl;
        this.clock = clock;
    }

    public record InviteCommand(String email, String firstName, String lastName, Set<Role> roles, LocalDate joinedOn) {
    }

    public record InvitationResult(long invitationId, long userId, long membershipId, String email,
                                   Instant expiresAt, String notificationStatus) {
    }

    @Transactional
    public InvitationResult invite(AuthenticatedUser actor, InviteCommand command) {
        rolePolicy.assertCanAssign(actor, command.roles());
        Club club = theClub();
        LocalDate joinedOn = command.joinedOn() != null ? command.joinedOn() : LocalDate.now(clock.withZone(club.getTimeZone()));

        UserRef user = userAccounts.createInvitedUser(command.email(), command.firstName(), command.lastName());
        ClubMembership membership = memberships.save(ClubMembership.active(club, user.userId(), joinedOn, command.roles()));
        statusEvents.save(new MembershipStatusEvent(membership.getId(), null, MembershipStatus.ACTIVE, clock.instant(),
                actor.userId()));

        IssuedInvitation issued = invitations.issue(club.getId(), user.userId(), actor.userId());
        EnqueueResult queued = enqueueEmail(club, user.userId(), issued);
        return new InvitationResult(issued.invitationId(), user.userId(), membership.getId(), user.email(),
                issued.expiresAt(), queued.notificationStatus());
    }

    /** New link for an account that has not been activated yet; the previous link stops working immediately. */
    @Transactional
    public InvitationResult resend(AuthenticatedUser actor, long membershipId) {
        ClubMembership membership = memberships.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("Član ne postoji."));
        UserProfile user = userAccounts.findProfile(membership.getUserId())
                .orElseThrow(() -> ApiException.notFound("Član ne postoji."));
        if (user.accountStatus() != AccountStatus.INVITED) {
            throw ApiException.businessRule("Račun je već aktiviran; pozivnica nije potrebna.");
        }
        Club club = theClub();
        IssuedInvitation issued = invitations.issue(club.getId(), user.userId(), actor.userId());
        EnqueueResult queued = enqueueEmail(club, user.userId(), issued);
        return new InvitationResult(issued.invitationId(), user.userId(), membershipId, user.email(),
                issued.expiresAt(), queued.notificationStatus());
    }

    private EnqueueResult enqueueEmail(Club club, long userId, IssuedInvitation issued) {
        UserProfile user = userAccounts.findProfile(userId).orElseThrow();
        // The token travels in the URL fragment: browsers never send it to servers/proxies (no logs, no Referer).
        String link = appUrl.link("/pozivnica#" + issued.token());
        Map<String, Object> payload = Map.of(
                "firstName", user.firstName(),
                "clubName", club.getName(),
                "invitationUrl", link,
                "expiresAt", EXPIRY_FORMAT.format(issued.expiresAt().atZone(club.getTimeZone())));
        return outbox.enqueue(OutboxMessage.of(EmailEventType.INVITATION, userId, user.email(), payload,
                "INVITATION:invitation-" + issued.invitationId()).withSecret());
    }

    private Club theClub() {
        List<Club> all = clubs.findAll();
        if (all.size() != 1) {
            throw new IllegalStateException("Expected exactly one club, found " + all.size());
        }
        return all.getFirst();
    }
}
