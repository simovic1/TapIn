package com.tapin.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {
}
