package com.tapin.members;

import com.tapin.auth.UserAccountService;
import com.tapin.auth.UserAccountService.UserProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public read API of the members module for other modules: the club and display data of memberships. Other modules
 * reference memberships by id only and never read the members tables directly.
 */
@Service
public class MembershipDirectory {

    private final ClubRepository clubs;
    private final ClubMembershipRepository memberships;
    private final UserAccountService userAccounts;
    private final List<CurrentBeltLookup> belts;

    MembershipDirectory(ClubRepository clubs, ClubMembershipRepository memberships, UserAccountService userAccounts,
                        List<CurrentBeltLookup> belts) {
        this.clubs = clubs;
        this.memberships = memberships;
        this.userAccounts = userAccounts;
        this.belts = belts;
    }

    /** Derived current belt per membership ({@code UNKNOWN} without belt history), e.g. for the roster. */
    @Transactional(readOnly = true)
    public Map<Long, String> currentBelts(Collection<Long> membershipIds) {
        Map<Long, String> result = new LinkedHashMap<>();
        membershipIds.forEach(id -> result.put(id, "UNKNOWN"));
        belts.forEach(lookup -> lookup.currentBelts(membershipIds).forEach((id, b) -> result.put(id, b.belt())));
        return result;
    }

    public record ClubRef(long clubId, String name, ZoneId timeZone) {
    }

    public record MemberRef(long membershipId, long userId, String firstName, String lastName, String email,
                            Set<Role> roles, MembershipStatus status) {

        /** Holds an operational staff role (trainer capabilities; ADMIN includes them, spec §2.1). */
        public boolean isStaff() {
            return roles.contains(Role.TRAINER) || roles.contains(Role.ADMIN);
        }
    }

    /** The single club of the MVP. */
    @Transactional(readOnly = true)
    public ClubRef theClub() {
        List<Club> all = clubs.findAll();
        if (all.size() != 1) {
            throw new IllegalStateException("Expected exactly one club, found " + all.size());
        }
        Club club = all.getFirst();
        return new ClubRef(club.getId(), club.getName(), club.getTimeZone());
    }

    /** Active memberships with an operational role (TRAINER or ADMIN), e.g. candidates for session coaches. */
    @Transactional(readOnly = true)
    public List<MemberRef> findActiveStaff() {
        List<Long> ids = memberships.findActiveStaff().stream().map(ClubMembership::getId).toList();
        return findAll(ids).values().stream()
                .sorted(java.util.Comparator.comparing(MemberRef::lastName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MemberRef::firstName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** @return memberships by id in the order requested; unknown ids are absent from the map */
    @Transactional(readOnly = true)
    public Map<Long, MemberRef> findAll(Collection<Long> membershipIds) {
        if (membershipIds.isEmpty()) {
            return Map.of();
        }
        List<ClubMembership> found = memberships.findAllById(membershipIds);
        Map<Long, UserProfile> profiles = userAccounts.findProfiles(found.stream().map(ClubMembership::getUserId).toList());
        Map<Long, MemberRef> result = new LinkedHashMap<>();
        for (Long id : membershipIds) {
            found.stream().filter(m -> m.getId().equals(id)).findFirst().ifPresent(m -> {
                UserProfile user = profiles.get(m.getUserId());
                if (user != null) {
                    result.put(id, new MemberRef(m.getId(), m.getUserId(), user.firstName(), user.lastName(),
                            user.email(), m.getRoles(), m.getStatus()));
                }
            });
        }
        return result;
    }
}
