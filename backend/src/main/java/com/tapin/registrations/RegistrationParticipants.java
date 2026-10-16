package com.tapin.registrations;

import com.tapin.members.MembershipDirectory;
import com.tapin.scheduling.TrainingParticipants;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** registrations' implementation of the scheduling SPI: who is registered, counts and the member's own state. */
@Component
class RegistrationParticipants implements TrainingParticipants {

    private final RegistrationRepository registrations;
    private final MembershipDirectory directory;
    private final List<AttendanceStatusLookup> attendance;

    RegistrationParticipants(RegistrationRepository registrations, MembershipDirectory directory,
                             List<AttendanceStatusLookup> attendance) {
        this.registrations = registrations;
        this.directory = directory;
        this.attendance = attendance;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Participant> activeRegistrations(long trainingId) {
        List<Long> ids = registrations.activeMemberships(trainingId);
        return directory.findAll(ids).values().stream()
                .map(m -> new Participant(m.membershipId(), m.userId(), m.firstName(), m.email()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Integer> registeredCounts(Collection<Long> trainingIds) {
        return registrations.activeCounts(trainingIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, MemberState> memberStates(long membershipId, Collection<Long> trainingIds) {
        Map<Long, RegistrationStatus> mine = registrations.statusesOf(membershipId, trainingIds);
        Map<Long, String> recorded = new HashMap<>();
        attendance.forEach(lookup -> recorded.putAll(lookup.statusesFor(membershipId, trainingIds)));
        Map<Long, MemberState> states = new HashMap<>();
        for (Long id : trainingIds) {
            RegistrationStatus registration = mine.get(id);
            String status = recorded.get(id);
            if (registration != null || status != null) {
                states.put(id, new MemberState(registration != null ? registration.name() : "NONE",
                        status != null ? status : "UNMARKED"));
            }
        }
        return states;
    }
}
