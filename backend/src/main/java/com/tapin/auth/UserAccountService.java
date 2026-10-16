package com.tapin.auth;

import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Public API of the auth module for user accounts. Other modules reference users only by {@code userId}. */
@Service
public class UserAccountService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    UserAccountService(UserAccountRepository users, PasswordEncoder passwordEncoder, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Transactional
    public void updateContact(long userId, String email, String firstName, String lastName, String phone) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Korisnik ne postoji."));
        users.findByEmailIgnoreCase(email).filter(other -> !other.getId().equals(userId)).ifPresent(other -> { throw duplicateEmail(); });
        if (!user.getEmail().equalsIgnoreCase(email)) {
            // Links delivered to the old address must not grant access to the changed account.
            jdbc.update("UPDATE invitations SET revoked_at=now() WHERE user_id=? AND accepted_at IS NULL AND revoked_at IS NULL", userId);
            jdbc.update("UPDATE password_reset_tokens SET revoked_at=now() WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL", userId);
        }
        user.updateContact(email, firstName, lastName, phone);
        try { users.saveAndFlush(user); }
        catch (DataIntegrityViolationException ex) { throw duplicateEmail(); }
    }

    /**
     * Own profile fields ({@code null} = unchanged): phone ({@code phoneSent} distinguishes "clear" from "absent")
     * and the training reminder preference (decision D-04).
     */
    @Transactional
    public void updateOwnProfile(long userId, long expectedVersion, boolean phoneSent, String phone,
                                 Boolean trainingRemindersEnabled) {
        // Same lock order as member administration; changing a phone invalidates the member editor's version too.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + 0x7A91_0001L + ")");
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Korisnik ne postoji."));
        if (user.getVersion() != expectedVersion) throw ApiException.versionConflict(findProfile(userId).orElseThrow());
        if (phone != null && !phone.matches("^[+0-9 ()/-]{6,32}$"))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Telefon mora sadržavati 6–32 dopuštena znaka.");
        if (phoneSent) {
            user.updateContact(user.getEmail(), user.getFirstName(), user.getLastName(), phone);
        }
        if (trainingRemindersEnabled != null) {
            user.setTrainingRemindersEnabled(trainingRemindersEnabled);
        }
        users.saveAndFlush(user);
        jdbc.update("UPDATE club_memberships SET version=version+1, updated_at=now() WHERE user_id=?", userId);
    }

    /** Users (of {@code userIds}) who want the training reminder. */
    @Transactional(readOnly = true)
    public java.util.Set<Long> withTrainingReminders(Collection<Long> userIds) {
        java.util.Set<Long> result = new java.util.HashSet<>();
        users.findAllById(userIds).forEach(u -> {
            if (u.isTrainingRemindersEnabled()) {
                result.add(u.getId());
            }
        });
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<UserRef> findByEmail(String email) {
        return users.findByEmailIgnoreCase(email.strip()).map(UserAccountService::toRef);
    }

    @Transactional(readOnly = true)
    public Optional<UserRef> findById(long userId) {
        return users.findById(userId).map(UserAccountService::toRef);
    }

    /**
     * Creates an INVITED account (no password). E-mail is unique regardless of case: an existing address gives
     * {@code 409 DUPLICATE_RESOURCE} and nothing is created (also under concurrent requests, via the unique index).
     */
    @Transactional
    public UserRef createInvitedUser(String email, String firstName, String lastName) {
        String normalized = email.strip();
        if (users.findByEmailIgnoreCase(normalized).isPresent()) {
            throw duplicateEmail();
        }
        try {
            return toRef(users.saveAndFlush(UserAccount.invited(normalized, firstName.strip(), lastName.strip())));
        } catch (DataIntegrityViolationException e) {
            throw duplicateEmail();
        }
    }

    private static ApiException duplicateEmail() {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Korisnik s tom e-mail adresom već postoji.");
    }

    /** Creates an ACTIVE account. The raw password is hashed here and never stored or logged. */
    @Transactional
    public UserRef createActiveUser(String email, String firstName, String lastName, String rawPassword) {
        UserAccount user = users.save(UserAccount.active(email.strip(), firstName.strip(), lastName.strip(),
                passwordEncoder.encode(rawPassword)));
        return toRef(user);
    }

    @Transactional(readOnly = true)
    public Optional<UserProfile> findProfile(long userId) {
        return users.findById(userId).map(UserAccountService::profile);
    }

    /** Batch lookup for display (names of coaches, notification recipients); unknown ids are skipped. */
    @Transactional(readOnly = true)
    public Map<Long, UserProfile> findProfiles(Collection<Long> userIds) {
        Map<Long, UserProfile> result = new HashMap<>();
        users.findAllById(userIds).forEach(u -> result.put(u.getId(), profile(u)));
        return result;
    }

    /** Locks before loading contact data so links cannot be issued to a concurrently replaced address. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<UserProfile> lockProfile(long userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
        return findProfile(userId);
    }

    public record UserProfile(long userId, String email, String firstName, String lastName, String phone,
                              AccountStatus accountStatus, long version, boolean trainingRemindersEnabled) {
    }

    private static UserProfile profile(UserAccount u) {
        return new UserProfile(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getPhone(),
                u.getAccountStatus(), u.getVersion(), u.isTrainingRemindersEnabled());
    }

    private static UserRef toRef(UserAccount user) {
        return new UserRef(user.getId(), user.getEmail(), user.getAccountStatus());
    }

    public record UserRef(long userId, String email, AccountStatus accountStatus) {
    }
}
