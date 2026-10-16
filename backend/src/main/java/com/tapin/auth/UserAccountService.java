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

    UserAccountService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
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
        return users.findById(userId).map(u -> new UserProfile(u.getId(), u.getEmail(), u.getFirstName(),
                u.getLastName(), u.getPhone(), u.getAccountStatus(), u.getVersion()));
    }

    /** Batch lookup for display (names of coaches, notification recipients); unknown ids are skipped. */
    @Transactional(readOnly = true)
    public Map<Long, UserProfile> findProfiles(Collection<Long> userIds) {
        Map<Long, UserProfile> result = new HashMap<>();
        users.findAllById(userIds).forEach(u -> result.put(u.getId(), new UserProfile(u.getId(), u.getEmail(),
                u.getFirstName(), u.getLastName(), u.getPhone(), u.getAccountStatus(), u.getVersion())));
        return result;
    }

    public record UserProfile(long userId, String email, String firstName, String lastName, String phone,
                              AccountStatus accountStatus, long version) {
    }

    private static UserRef toRef(UserAccount user) {
        return new UserRef(user.getId(), user.getEmail(), user.getAccountStatus());
    }

    public record UserRef(long userId, String email, AccountStatus accountStatus) {
    }
}
