package com.tapin.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Creates an ACTIVE account. The raw password is hashed here and never stored or logged. */
    @Transactional
    public UserRef createActiveUser(String email, String firstName, String lastName, String rawPassword) {
        UserAccount user = users.save(UserAccount.active(email.strip(), firstName.strip(), lastName.strip(),
                passwordEncoder.encode(rawPassword)));
        return toRef(user);
    }

    private static UserRef toRef(UserAccount user) {
        return new UserRef(user.getId(), user.getEmail(), user.getAccountStatus());
    }

    public record UserRef(long userId, String email, AccountStatus accountStatus) {
    }
}
