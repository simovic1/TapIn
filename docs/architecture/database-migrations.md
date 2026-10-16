# Flyway migracije

Shemu baze isključivo vodi **Flyway** (`backend/src/main/resources/db/migration`). Hibernate radi samo
`ddl-auto: validate` – nikad `update`/`create` (§13.1).

## Imenovanje

| Vrsta | Format | Primjer |
|---|---|---|
| Verzionirana | `V{NNN}__{modul}_{opis}.sql` | `V008__attendance_attendances_audit.sql` |
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
ova tablica u istom PR-u. (Outbox je zbog BJJ-05 dobio V004, pa su planirane migracije V005–V009 pomaknute za
jedan; komentar u `V001` ostaje izvorni jer se primijenjena migracija ne mijenja.) BJJ-08 isporučuje reset
lozinke kao V006; još neisporučene domenske migracije sada su rezervirane kao V007–V010.

| Verzija | Datoteka | Modul | Sadržaj | Faza |
|---|---|---|---|---|
| V001 | `V001__shared_baseline.sql` | shared | Baseline, bez tablica | 0 ✅ |
| V002 | `V002__auth_users.sql` | auth | `users` (email jedinstven bez obzira na velika/mala slova – `uk_users_email_lower`, `account_status`) | 1 ✅ |
| V003 | `V003__members_clubs_memberships_roles.sql` | members | `clubs` (+ seed jednog kluba `Europe/Zagreb`, `uk_clubs_single_club`), `club_memberships` (`uk_club_memberships_club_user`), `membership_roles` (`pk_membership_roles`), `membership_status_events` | 1 ✅ |
| V004 | `V004__notifications_email_outbox.sql` | notifications | `email_outbox` (outbox, deduplikacija, lease) – BJJ-05 | 5 ✅ |
| V005 | `V005__auth_invitations.sql` | auth | `invitations` (hash tokena, jedna otvorena pozivnica po korisniku) – BJJ-07 | 1 ✅ |
| V006 | `V006__auth_password_reset_tokens.sql` | auth | `password_reset_tokens` (odvojeno od pozivnica) | 1 ✅ |
| V007 | `V007__scheduling_recurring_schedules_sessions.sql` | scheduling | `recurring_schedules` (+ seed uto/čet 18:30–20:00), `training_sessions` (`uk_training_sessions_schedule_occurrence`, `ex_training_sessions_no_overlap`), `session_coaches`, `training_session_events`; FK `email_outbox.session_id` → `training_sessions` – BJJ-13 | 2 ✅ |
| V008 | `V008__registrations_training_registrations.sql` | registrations | `training_registrations` (`uk_training_registrations_session_membership`, FK na isti klub), `registration_events` – BJJ-17 | 2 ✅ |
| V009 | `V009__attendance_attendances_audit.sql` | attendance | `training_attendances`, `attendance_audit_logs` | 3 |
| V010 | `V010__belts_belt_periods_audit.sql` | belts | `member_belt_periods`, `belt_period_audit_logs` | 4 |
| R__ | `R__reporting_*.sql` | reporting | Read-only pogledi za statistiku | 6 |

Admin seed (prvi administrator) **nije** u migraciji jer zahtijeva tajnu. Radi ga `AdminBootstrapService`
(modul `members`) pri startu, iz `ADMIN_EMAIL`/`ADMIN_PASSWORD`:

- radi samo dok ne postoji aktivni administrator – kasniji startovi ništa ne mijenjaju (ni lozinku);
- postojećem korisniku s tim e-mailom (bez obzira na velika/mala slova) samo dodaje ulogu `ADMIN`;
- za novi račun zahtijeva lozinku od najmanje 12 znakova, inače start pada; lozinka se nikad ne logira;
- PostgreSQL advisory lock sprječava duplikate pri istodobnom startu više instanci;
- zadana lozinka postoji **samo** u `application-local.yml` (lokalni razvoj); staging/prod je nemaju.

## Vlasništvo domenskih migracija

Migracije V004+ isporučuje vlasnik modula u zadatku koji uvodi te tablice, prema redoslijedu iz tablice gore.
Ako zadatak treba tablicu iz kasnijeg broja prije ranijeg, dogovara se zamjena brojeva u tablici (isti PR).

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
