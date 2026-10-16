package com.tapin.members;

import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipStatusEventRepository extends JpaRepository<MembershipStatusEvent, Long> {
}
