package com.tapin.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** Case-insensitive lookup backed by the unique index {@code uk_users_email_lower}. */
    @Query("select u from UserAccount u where lower(u.email) = lower(:email)")
    Optional<UserAccount> findByEmailIgnoreCase(String email);
}
