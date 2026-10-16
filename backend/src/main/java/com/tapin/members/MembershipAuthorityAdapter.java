package com.tapin.members;

import com.tapin.auth.MembershipAuthorityLookup;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.stream.Collectors;

/** members' implementation of the auth SPI: the user's membership in the (single) club and its roles. */
@Component
class MembershipAuthorityAdapter implements MembershipAuthorityLookup {

    private final ClubMembershipRepository memberships;

    MembershipAuthorityAdapter(ClubMembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipAuthority> findForUser(long userId) {
        return memberships.findFirstByUserIdOrderByIdAsc(userId).map(m -> new MembershipAuthority(
                m.getId(),
                m.getRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                m.getStatus().name(),
                m.getJoinedOn(),
                m.getClub().getName(),
                m.getClub().getTimeZone().getId()));
    }
}
