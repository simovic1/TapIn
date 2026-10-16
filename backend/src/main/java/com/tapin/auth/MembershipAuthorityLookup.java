package com.tapin.auth;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * SPI implemented by the members module: gives auth the club membership and roles of a user without auth
 * depending on members (dependency inversion keeps the module graph acyclic).
 */
public interface MembershipAuthorityLookup {

    Optional<MembershipAuthority> findForUser(long userId);

    /**
     * @param roles            role names ({@code MEMBER}, {@code TRAINER}, {@code ADMIN})
     * @param membershipStatus {@code ACTIVE} or {@code INACTIVE}; inactive members may still sign in to see their
     *                         history, business rules decide what they may do
     */
    record MembershipAuthority(long membershipId, Set<String> roles, String membershipStatus, LocalDate joinedOn,
                               String clubName, String clubTimeZone) {
    }
}
