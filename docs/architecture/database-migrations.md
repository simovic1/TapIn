# Flyway migracije

Shemu baze isključivo vodi **Flyway** (`backend/src/main/resources/db/migration`). Hibernate radi samo
`ddl-auto: validate` – nikad `update`/`create` (§13.1).

## Imenovanje

| Vrsta | Format | Primjer |
|---|---|---|
| Verzionirana | `V{NNN}__{modul}_{opis}.sql` | `V007__attendance_attendances_audit.sql` |
| Repeatable (pogledi, funkcije) | `R__{modul}_{opis}.sql` | `R__reporting_member_attendance_view.sql` |

- `NNN` – troznamenkasti, **globalni, uzastopni** broj (jedna povijest za cijeli monolit).
- `modul` – vlasnik migracije: `shared | auth | members | belts | scheduling | registrations | attendance | reporting | notifications`.
- `opis` – `snake_case`, kratko, engleski (kao i nazivi tablica).
- **Jedna migracija = jedan modul.** Migracija smije stvarati/mijenjati samo tablice svog modula; strani ključevi
  smiju pokazivati na tablice iz *ranijih* migracija.

CI (`scripts/check-migrations.sh`) odbija neispravan naziv, duplikat ili rupu u numeraciji. Lokalno:

```bash
sh scripts/check-migrations.sh
```

## Dogovoreni redoslijed (MVP)

Redoslijed slijedi ovisnosti tablica i backlog (§16). Brojevi su rezervirani; ako se plan promijeni, ažurira se
ova tablica u istom PR-u.

| Verzija | Datoteka | Modul | Sadržaj | Faza |
|---|---|---|---|---|
| V001 | `V001__shared_baseline.sql` | shared | Baseline, bez tablica | 0 ✅ |
| V002 | `V002__auth_users.sql` | auth | `users` (email `UNIQUE` case-insensitive, `account_status`) | 1 |
| V003 | `V003__members_clubs_memberships_roles.sql` | members | `clubs` (+ seed kluba), `club_memberships`, `membership_roles`, `membership_status_events` | 1 |
| V004 | `V004__auth_invitations_password_resets.sql` | auth | `invitations`, `password_reset_tokens` | 1 |
| V005 | `V005__scheduling_recurring_schedules_sessions.sql` | scheduling | `recurring_schedules` (+ seed uto/čet 18:30–20:00), `training_sessions`, `session_coaches` | 2 |
| V006 | `V006__registrations_training_registrations.sql` | registrations | `training_registrations`, `registration_events` | 2 |
| V007 | `V007__attendance_attendances_audit.sql` | attendance | `training_attendances`, `attendance_audit_logs` | 3 |
| V008 | `V008__belts_belt_periods_audit.sql` | belts | `member_belt_periods`, `belt_period_audit_logs` | 4 |
| V009 | `V009__notifications_email_outbox.sql` | notifications | `email_outbox` | 5 |
| R__ | `R__reporting_*.sql` | reporting | Read-only pogledi za statistiku | 6 |

Admin seed (prvi administrator) **nije** u migraciji jer zahtijeva tajnu (lozinku/e-mail) – radi se aplikacijskim
seederom iz varijabli okruženja u Fazi 1.

## Pravila

1. **Primijenjena migracija se nikad ne mijenja.** Ispravak = nova migracija. (Flyway validacija pada ako se
   checksum promijeni.)
2. **Paralelne grane:** prije merge-a rebase na `master` i, ako je broj zauzet, preimenovati svoju migraciju na
   sljedeći slobodan broj (i ažurirati tablicu gore).
3. **Destruktivne promjene** (drop/rename stupca) u dva koraka kroz dvije verzije: najprije dodati novo i
   migrirati podatke, tek zatim ukloniti staro.
4. **Podaci vs. shema:** seed podataka koji su dio domene (klub, osnovni raspored) smije biti u migraciji
   vlasnika; testni podaci nikada.
5. U produkciji je `flyway.clean-disabled: true`.

## SQL konvencije

| Element | Konvencija | Primjer |
|---|---|---|
| Tablica | `snake_case`, množina | `training_sessions` |
| Stupac | `snake_case` | `effective_from_date` |
| Primarni ključ | `id BIGINT GENERATED ALWAYS AS IDENTITY` | |
| Strani ključ (stupac) | `<entitet>_id` | `membership_id` |
| Trenutak (UTC) | `TIMESTAMPTZ`, sufiks `_at` | `registered_at` |
| Kalendarski datum | `DATE`, sufiks `_on` ili `_date` | `joined_on`, `effective_from_date` |
| Lokalno vrijeme | `TIME` | `start_time` |
| Status / enum | `VARCHAR(32)` + `CHECK` | `status IN ('SCHEDULED','CANCELLED','HELD')` |
| Optimistično zaključavanje | `version BIGINT NOT NULL DEFAULT 0` | |
| Constrainti | `pk_<tablica>`, `fk_<tablica>_<referenca>`, `uk_<tablica>_<stupci>`, `ck_<tablica>_<pravilo>` | `uk_training_attendances_session_membership` |
| Indeksi | `ix_<tablica>_<stupci>` | `ix_training_sessions_start_at_status` |
