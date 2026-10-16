package com.tapin.members;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

interface ClubMembershipRepository extends JpaRepository<ClubMembership, Long> {

    Optional<ClubMembership> findByClubIdAndUserId(Long clubId, Long userId);

    /** MVP has one club, so a user has at most one membership. */
    @EntityGraph(attributePaths = "club")
    Optional<ClubMembership> findFirstByUserIdOrderByIdAsc(Long userId);

    @Query("select m from ClubMembership m join m.roles r "
            + "where m.status = com.tapin.members.MembershipStatus.ACTIVE and r = com.tapin.members.Role.ADMIN")
    List<ClubMembership> findActiveAdmins();
}
