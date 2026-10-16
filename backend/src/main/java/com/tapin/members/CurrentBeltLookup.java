package com.tapin.members;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * SPI implemented by the belts module (which depends on members, not the other way round): the derived current
 * belt of memberships. Without an implementation every member is {@code UNKNOWN}.
 */
public interface CurrentBeltLookup {

    /** Current belt per membership; memberships without belt history are absent (= UNKNOWN). */
    Map<Long, CurrentBelt> currentBelts(Collection<Long> membershipIds);

    /** Memberships whose current belt is {@code belt} (WHITE..BLACK). */
    Set<Long> membershipsWithCurrentBelt(String belt);

    /** Memberships with any belt history (the complement are the UNKNOWN ones). */
    Set<Long> membershipsWithBeltHistory();

    /** @param awardedOn real award date of the current belt; {@code null} = "datum nije evidentiran" */
    record CurrentBelt(String belt, LocalDate awardedOn) {
    }
}
