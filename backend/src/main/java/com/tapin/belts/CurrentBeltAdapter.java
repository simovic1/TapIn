package com.tapin.belts;

import com.tapin.members.CurrentBeltLookup;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** belts' implementation of the members SPI: the current belt is the latest period (spec §3.1, §10). */
@Component
class CurrentBeltAdapter implements CurrentBeltLookup {

    private final BeltPeriodRepository periods;

    CurrentBeltAdapter(BeltPeriodRepository periods) {
        this.periods = periods;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, CurrentBelt> currentBelts(Collection<Long> membershipIds) {
        Map<Long, CurrentBelt> result = new HashMap<>();
        periods.currentOf(membershipIds).forEach((id, p) -> result.put(id, new CurrentBelt(p.belt().name(), p.awardedOn())));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> membershipsWithCurrentBelt(String belt) {
        return periods.withCurrentBelt(Belt.valueOf(belt));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> membershipsWithBeltHistory() {
        return periods.withHistory();
    }
}
