package com.tapin.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface RecurringScheduleRepository extends JpaRepository<RecurringSchedule, Long> {

    List<RecurringSchedule> findByEnabledTrue();
}
