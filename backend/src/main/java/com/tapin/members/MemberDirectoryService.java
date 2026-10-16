package com.tapin.members;

import com.tapin.auth.AuthenticatedUser;
import com.tapin.auth.UserAccountService;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import com.tapin.shared.web.ProfilePatch;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class MemberDirectoryService {
    // Shared with administrator bootstrap: serializes all changes that can remove the last active admin.
    private static final long ADMIN_LOCK = 0x7A91_0001L;
    private static final String SELECT = """
            SELECT m.id, m.user_id, u.first_name, u.last_name, u.email, u.phone, u.account_status,
                   m.status, m.joined_on, m.created_at, GREATEST(m.updated_at, u.updated_at) AS updated_at, m.version,
                   (SELECT string_agg(r.role, ',' ORDER BY r.role) FROM membership_roles r WHERE r.membership_id = m.id) AS roles
            FROM club_memberships m JOIN users u ON u.id = m.user_id
            """;
    private final JdbcTemplate jdbc;
    private final UserAccountService users;
    private final Validator validator;
    private final Clock clock;

    MemberDirectoryService(JdbcTemplate jdbc, UserAccountService users, Validator validator, Clock clock) {
        this.jdbc = jdbc; this.users = users; this.validator = validator; this.clock = clock;
    }

    public record StatusEvent(String oldStatus, String newStatus, Instant effectiveAt, Long actorUserId) {}
    public record MemberDetail(long membershipId, long userId, String firstName, String lastName, String email,
                               String phone, List<String> roles, String accountStatus, String membershipStatus,
                               LocalDate joinedOn, String currentBelt, LocalDate currentBeltAwardedOn,
                               LocalDate lastPresentOn, Instant createdAt, Instant updatedAt, long version,
                               List<StatusEvent> statusHistory) {}
    public record PageMetadata(int page, int size, long totalElements, long totalPages) {}
    public record MemberPage(List<MemberDetail> items, PageMetadata page) {}
    record Contact(@NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String lastName,
                   @NotBlank @Email @Size(max = 320) String email,
                   @Pattern(regexp = "^[+0-9 ()/-]{6,32}$") String phone) {}

    @Transactional(readOnly = true)
    public MemberPage list(String query, MembershipStatus status, Role role, String belt, int page, int size, String sort) {
        if (query.length() > 100 || page < 0 || size < 1 || size > 100) throw ProfilePatch.invalid("Neispravna pretraga ili paginacija.");
        if (!sort.matches("(lastName|firstName|joinedOn|lastPresentOn),(asc|desc)")) throw ProfilePatch.invalid("Nepodržano sortiranje.");
        if (belt != null && !Set.of("WHITE", "BLUE", "PURPLE", "BROWN", "BLACK", "UNKNOWN").contains(belt))
            throw ProfilePatch.invalid("Nepoznat pojas.");
        // Belt/attendance modules have not supplied derived data yet; do not invent it.
        String filter = " WHERE (strpos(lower(u.first_name || ' ' || u.last_name), lower(?)) > 0 OR strpos(lower(u.email), lower(?)) > 0)";
        List<Object> args = new ArrayList<>(List.of(query.strip(), query.strip()));
        if (status != null) { filter += " AND m.status = ?"; args.add(status.name()); }
        if (role != null) { filter += " AND EXISTS (SELECT 1 FROM membership_roles r WHERE r.membership_id=m.id AND r.role=?)"; args.add(role.name()); }
        if (belt != null && !belt.equals("UNKNOWN")) filter += " AND false";
        long total = jdbc.queryForObject("SELECT count(*) FROM club_memberships m JOIN users u ON u.id=m.user_id" + filter, Long.class, args.toArray());
        String column = switch (sort.split(",")[0]) {
            case "firstName" -> "lower(u.first_name)";
            case "joinedOn" -> "m.joined_on";
            default -> "lower(u.last_name)";
        };
        args.add(size); args.add((long) page * size);
        var items = jdbc.query(SELECT + filter + " ORDER BY " + column + (sort.endsWith("desc") ? " DESC" : " ASC")
                + ", m.id ASC LIMIT ? OFFSET ?", (rs, row) -> map(rs, false), args.toArray());
        return new MemberPage(items, new PageMetadata(page, size, total, (total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public MemberDetail detail(long id) {
        return jdbc.query(SELECT + " WHERE m.id = ?", (rs, row) -> map(rs, true), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Član ne postoji."));
    }

    private MemberDetail map(ResultSet rs, boolean history) throws SQLException {
        long id = rs.getLong("id");
        List<StatusEvent> events = history ? jdbc.query("SELECT old_status, new_status, effective_at, actor_user_id FROM membership_status_events WHERE membership_id=? ORDER BY effective_at, id",
                (event, row) -> new StatusEvent(event.getString(1), event.getString(2), event.getTimestamp(3).toInstant(), event.getObject(4, Long.class)), id) : List.of();
        String roles = rs.getString("roles");
        return new MemberDetail(id, rs.getLong("user_id"), rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
                rs.getString("phone"), roles == null ? List.of() : Arrays.asList(roles.split(",")), rs.getString("account_status"), rs.getString("status"),
                rs.getObject("joined_on", LocalDate.class), "UNKNOWN", null, null, rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"), events);
    }

    @Transactional
    public MemberDetail update(AuthenticatedUser actor, long id, Map<String, JsonNode> fields) {
        var patch = new ProfilePatch(fields, Set.of("expectedVersion", "firstName", "lastName", "email", "phone", "joinedOn", "membershipStatus"));
        lockAndAuthorize(actor);
        var current = detail(id);
        checkVersion(current, patch.version());
        var contact = new Contact(patch.text("firstName", current.firstName()), patch.text("lastName", current.lastName()),
                patch.text("email", current.email()), patch.text("phone", current.phone()));
        if (!validator.validate(contact).isEmpty()) throw ProfilePatch.invalid("Provjerite ime, prezime, e-mail i telefon (6–32 dopuštena znaka).");
        LocalDate joinedOn = patch.date("joinedOn", current.joinedOn());
        validateJoinedOn(joinedOn);
        String status = patch.text("membershipStatus", current.membershipStatus());
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) throw ProfilePatch.invalid("Neispravan status članstva.");
        protectLastAdmin(current, current.roles().contains("ADMIN") && status.equals("ACTIVE"));
        users.updateContact(current.userId(), contact.email(), contact.firstName(), contact.lastName(), contact.phone());
        jdbc.update("UPDATE club_memberships SET joined_on=?, status=?, version=version+1, updated_at=? WHERE id=?",
                joinedOn, status, Timestamp.from(clock.instant()), id);
        ensureBaseline(id, joinedOn, current.membershipStatus(), actor.userId());
        if (!status.equals(current.membershipStatus())) {
            jdbc.update("INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at, actor_user_id) VALUES (?,?,?,?,?)",
                    id, current.membershipStatus(), status, Timestamp.from(clock.instant()), actor.userId());
        }
        return detail(id);
    }

    @Transactional
    public MemberDetail replaceRoles(AuthenticatedUser actor, long id, long expectedVersion, Set<Role> roles) {
        lockAndAuthorize(actor);
        var current = detail(id);
        checkVersion(current, expectedVersion);
        if (roles == null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull)) throw ProfilePatch.invalid("Odaberite najmanje jednu ulogu.");
        protectLastAdmin(current, roles.contains(Role.ADMIN) && current.membershipStatus().equals("ACTIVE"));
        jdbc.update("DELETE FROM membership_roles WHERE membership_id=?", id);
        for (Role role : roles) jdbc.update("INSERT INTO membership_roles (membership_id, role) VALUES (?,?)", id, role.name());
        jdbc.update("UPDATE club_memberships SET version=version+1, updated_at=? WHERE id=?", Timestamp.from(clock.instant()), id);
        return detail(id);
    }

    private void lockAndAuthorize(AuthenticatedUser actor) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + ADMIN_LOCK + ")");
        // Recheck after locking: a concurrent request may have removed this actor's role.
        Integer allowed = jdbc.queryForObject("SELECT count(*) FROM membership_roles r JOIN club_memberships m ON m.id=r.membership_id JOIN users u ON u.id=m.user_id WHERE m.user_id=? AND r.role='ADMIN' AND u.account_status='ACTIVE'", Integer.class, actor.userId());
        if (allowed == null || allowed == 0) throw new ApiException(ErrorCode.FORBIDDEN, "Samo administrator smije mijenjati članstvo.");
    }

    private void protectLastAdmin(MemberDetail current, boolean remainsAdmin) {
        if (remainsAdmin || !current.roles().contains("ADMIN") || !current.membershipStatus().equals("ACTIVE") || !current.accountStatus().equals("ACTIVE")) return;
        long others = jdbc.queryForObject("SELECT count(*) FROM club_memberships m JOIN users u ON u.id=m.user_id JOIN membership_roles r ON r.membership_id=m.id WHERE m.id<>? AND m.status='ACTIVE' AND u.account_status='ACTIVE' AND r.role='ADMIN'", Long.class, current.membershipId());
        if (others == 0) throw new ApiException(ErrorCode.LAST_ADMIN_REMOVAL, "Zadnji aktivni administrator ne može izgubiti pristup.");
    }

    private static void checkVersion(MemberDetail current, long expected) {
        if (current.version() != expected) throw ApiException.versionConflict(current);
    }

    public void validateJoinedOn(LocalDate joinedOn) {
        if (joinedOn == null || joinedOn.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Europe/Zagreb")))))
            throw ProfilePatch.invalid("Datum učlanjenja je obvezan i ne smije biti u budućnosti.");
    }

    private void ensureBaseline(long id, LocalDate joinedOn, String fallbackStatus, long actorId) {
        var start = Timestamp.from(joinedOn.atStartOfDay(ZoneId.of("Europe/Zagreb")).toInstant());
        jdbc.update("""
                INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at, actor_user_id)
                SELECT ?, NULL, COALESCE((SELECT COALESCE(old_status,new_status) FROM membership_status_events WHERE membership_id=? ORDER BY effective_at,id LIMIT 1), ?), ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM membership_status_events WHERE membership_id=? AND effective_at<=?)
                """, id, id, fallbackStatus, start, actorId, id, start);
    }

    /** Shared by future attendance/reporting modules; intervals are [activation, deactivation). */
    @Transactional(readOnly = true)
    public boolean activeAt(long membershipId, Instant instant) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT joined_on <= (? AT TIME ZONE 'Europe/Zagreb')::date AND
                COALESCE((SELECT new_status='ACTIVE' FROM membership_status_events WHERE membership_id=m.id AND effective_at<=? ORDER BY effective_at DESC,id DESC LIMIT 1),false)
                FROM club_memberships m WHERE id=?
                """, Boolean.class, Timestamp.from(instant), Timestamp.from(instant), membershipId));
    }
}
