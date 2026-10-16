package com.tapin.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Faza 0 placeholder: prevents Spring Boot from creating its default in-memory user with a generated password.
 * Replace with the JPA-backed implementation over {@code users} in Faza 1 (session login).
 */
@Service
class NoUsersYetUserDetailsService implements UserDetailsService {

    @Override
    public UserDetails loadUserByUsername(String username) {
        throw new UsernameNotFoundException("User accounts are not implemented yet");
    }
}
