package com.tapin.reporting;

import com.tapin.shared.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ClubReportsService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    ClubReportsService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    record Member(long membershipId, long userId, String firstName, String lastName, String email,
                  String currentBelt, String membershipStatus, LocalDate joinedOn, LocalDate lastPresentOn) {}
    public record Row(long membershipId, long userId, String firstName, String lastName, String email,
                      String currentBelt, String membershipStatus, int presentCount, int presentThisMonth,
                      int presentLast7Days, int presentLast30Days, int registeredButAbsent,
                      int decidedRegistrations, int unmarkedRegistrations, Double noShowRate, LocalDate lastPresentOn) {}
    public record Page(int page, int size, int totalElements, int totalPages) {}
    public record Session(long trainingId, LocalDate localDate, int totalPresent) {}
    public record Month(String month, int heldSessions, int totalPresent, Double averagePresentPerSession) {}
    public record Totals(int heldSessions, int totalPresent, Double averagePresentPerSession, List<Month> monthly, List<Session> sessions) {}
    public record Report(LocalDate from, LocalDate to, Totals totals, List<Row> items, Page page) {}
    public record Inactive(long membershipId, long userId, String firstName, String lastName, String currentBelt,
                           LocalDate lastPresentOn, Long daysSinceLastPresent) {}
    public record InactivePage(List<Inactive> items, Page page) {}
    record Fact(long member, long session, LocalDate date, String attendance, String registration) {}
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Europe/Zagreb"))); }
    private List<Member> members(Long id, String belt) {
        if (belt != null && !Set.of("WHITE", "BLUE", "PURPLE", "BROWN", "BLACK", "UNKNOWN").contains(belt))
            throw ApiException.invalidField("belt", "Nepoznat pojas.");
        return jdbc.query("""
            SELECT m.id, m.user_id, u.first_name, u.last_name, u.email, m.status, m.joined_on,
              COALESCE((SELECT b.belt FROM member_belt_periods b WHERE b.membership_id=m.id
                ORDER BY b.effective_from_date DESC,b.id DESC LIMIT 1),'UNKNOWN') AS belt,
              (SELECT max((s.start_at AT TIME ZONE 'Europe/Zagreb')::date)
                FROM training_attendances a JOIN training_sessions s ON s.id=a.session_id
                WHERE a.membership_id=m.id AND a.status='PRESENT' AND s.status='HELD') AS last_present
            FROM club_memberships m JOIN users u ON u.id=m.user_id ORDER BY m.id
            """, (r,n) -> new Member(r.getLong("id"), r.getLong("user_id"), r.getString("first_name"),
                r.getString("last_name"), r.getString("email"), r.getString("belt"), r.getString("status"),
                r.getObject("joined_on",LocalDate.class),r.getObject("last_present",LocalDate.class)))
            .stream().filter(m -> (id == null || m.membershipId()==id) && (belt == null || belt.equals(m.currentBelt()))).toList();
    }
    public Report report(LocalDate from, LocalDate to, Long id, String belt, String sort, int page, int size) {
        validatePage(page,size);
        if (from == null || to == null || !from.isBefore(to) || ChronoUnit.DAYS.between(from,to)>366)
            throw ApiException.invalidField("to", "Razdoblje mora imati 1–366 dana; datum do je isključiv.");
        List<Member> members = members(id,belt);
        Map<Long,List<Fact>> byMember = new HashMap<>();
        Set<Long> ids = new HashSet<>(); members.forEach(m -> ids.add(m.membershipId()));
        List<Fact> facts = jdbc.query("""
            SELECT COALESCE(a.membership_id,r.membership_id) AS member_id, s.id, (s.start_at AT TIME ZONE 'Europe/Zagreb')::date AS day,
                COALESCE(a.status,'UNMARKED') AS attendance, COALESCE(r.status,'NONE') AS registration
            FROM training_attendances a FULL JOIN training_registrations r ON a.session_id=r.session_id AND a.membership_id=r.membership_id
            JOIN training_sessions s ON s.id=COALESCE(a.session_id,r.session_id)
            WHERE s.status='HELD' AND (s.start_at AT TIME ZONE 'Europe/Zagreb')::date >= ?
                AND (s.start_at AT TIME ZONE 'Europe/Zagreb')::date < ?
            """, (r,n) -> new Fact(r.getLong("member_id"),r.getLong("id"),r.getObject("day",LocalDate.class),
                r.getString("attendance"),r.getString("registration")),from,to);
        Map<Long,Integer> sessionCounts = new HashMap<>();
        for (Fact f: facts) if (ids.contains(f.member())) {
            byMember.computeIfAbsent(f.member(), k -> new ArrayList<>()).add(f);
            if (f.attendance().equals("PRESENT")) sessionCounts.merge(f.session(),1,Integer::sum);
        }
        List<Row> rows = new ArrayList<>(); LocalDate today=today();
        for (Member m: members) {
            int count=0, month=0, week=0, recent=0, absent=0, decided=0, unmarked=0;
            for (Fact f: byMember.getOrDefault(m.membershipId(),List.of())) {
                if (f.attendance().equals("PRESENT")) {
                    count++;
                    if (!f.date().isAfter(today)) {
                        if (!f.date().isBefore(today.withDayOfMonth(1))) month++;
                        if (!f.date().isBefore(today.minusDays(6))) week++;
                        if (!f.date().isBefore(today.minusDays(29))) recent++;
                    }
                }
                if (f.registration().equals("REGISTERED")) {
                    if (f.attendance().equals("UNMARKED")) unmarked++;
                    else { decided++; if (f.attendance().equals("ABSENT")) absent++; }
                }
            }
            rows.add(new Row(m.membershipId(),m.userId(),m.firstName(),m.lastName(),m.email(),m.currentBelt(),m.membershipStatus(),
                count,month,week,recent,absent,decided,unmarked,ratio(absent,decided),m.lastPresentOn()));
        }
        Comparator<Row> comparator = switch(sort) {
            case "presentCount,desc" -> Comparator.comparingInt(Row::presentCount).reversed();
            case "presentCount,asc" -> Comparator.comparingInt(Row::presentCount);
            case "lastName,asc" -> Comparator.comparing(Row::lastName,String.CASE_INSENSITIVE_ORDER);
            case "lastName,desc" -> Comparator.comparing(Row::lastName,String.CASE_INSENSITIVE_ORDER).reversed();
            case "noShowRate,asc" -> Comparator.comparing(Row::noShowRate,Comparator.nullsLast(Comparator.naturalOrder()));
            case "noShowRate,desc" -> Comparator.comparing(Row::noShowRate,Comparator.nullsLast(Comparator.reverseOrder()));
            case "lastPresentOn,asc" -> Comparator.comparing(Row::lastPresentOn,Comparator.nullsLast(Comparator.naturalOrder()));
            case "lastPresentOn,desc" -> Comparator.comparing(Row::lastPresentOn,Comparator.nullsLast(Comparator.reverseOrder()));
            default -> throw ApiException.invalidField("sort","Nepodržano sortiranje.");
        };
        rows.sort(comparator.thenComparingLong(Row::membershipId));
        List<Session> sessions = jdbc.query("""
            SELECT id,(start_at AT TIME ZONE 'Europe/Zagreb')::date AS day FROM training_sessions WHERE status='HELD'
              AND (start_at AT TIME ZONE 'Europe/Zagreb')::date >= ? AND (start_at AT TIME ZONE 'Europe/Zagreb')::date < ? ORDER BY start_at,id
            """, (r,n)->new Session(r.getLong("id"),r.getObject("day",LocalDate.class),sessionCounts.getOrDefault(r.getLong("id"),0)),from,to);
        List<Month> months = new ArrayList<>();
        for (YearMonth m=YearMonth.from(from); !m.isAfter(YearMonth.from(to.minusDays(1))); m=m.plusMonths(1)) {
            YearMonth key=m;
            List<Session> subset=sessions.stream().filter(s->YearMonth.from(s.localDate()).equals(key)).toList();
            int total=subset.stream().mapToInt(Session::totalPresent).sum();
            months.add(new Month(m.toString(),subset.size(),total,ratio(total,subset.size())));
        }
        int total=sessions.stream().mapToInt(Session::totalPresent).sum();
        return new Report(from,to,new Totals(sessions.size(),total,ratio(total,sessions.size()),months,sessions),
            slice(rows,page,size),metadata(rows.size(),page,size));
    }
    public InactivePage inactive(int days, int page, int size) {
        validatePage(page,size);
        if(days<1 || days>365) throw ApiException.invalidField("days","Prag mora biti 1–365 dana.");
        LocalDate today=today();
        List<Inactive> rows=members(null,null).stream().filter(m->m.membershipStatus().equals("ACTIVE"))
            .filter(m->ChronoUnit.DAYS.between(m.lastPresentOn()==null?m.joinedOn():m.lastPresentOn(),today)>=days)
            .map(m->new Inactive(m.membershipId(),m.userId(),m.firstName(),m.lastName(),m.currentBelt(),m.lastPresentOn(),
                m.lastPresentOn()==null?null:ChronoUnit.DAYS.between(m.lastPresentOn(),today)))
            .sorted(Comparator.comparing(Inactive::lastPresentOn,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingLong(Inactive::membershipId)).toList();
        return new InactivePage(slice(rows,page,size),metadata(rows.size(),page,size));
    }
    private static Double ratio(int a,int b) { return b==0?null:(double)a/b; }
    private static void validatePage(int page,int size) { if(page<0||size<1) throw ApiException.invalidField("page","Neispravna paginacija."); }
    private static Page metadata(int n,int page,int size) { return new Page(page,size,n,(int)(((long)n+size-1)/size)); }
    private static <T> List<T> slice(List<T> rows,int page,int size) { int start=(int)Math.min((long)page*size,rows.size());return rows.subList(start,(int)Math.min((long)start+size,rows.size())); }
}
