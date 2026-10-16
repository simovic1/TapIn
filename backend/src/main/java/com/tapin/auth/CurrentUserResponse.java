package com.tapin.auth;

import java.time.LocalDate;
import java.util.List;

/** {@code CurrentUser} from the API contract. */
public record CurrentUserResponse(
        long userId,
        long membershipId,
        String email,
        String firstName,
        String lastName,
        String phone,
        AccountStatus accountStatus,
        String membershipStatus,
        List<String> roles,
        LocalDate joinedOn,
        Club club,
        long version,
        boolean trainingRemindersEnabled) {

    public record Club(String name, String timeZone) {
    }
}
