# Moduli i vlasništvo

TapIn je **modularni monolit** (specifikacija §13.2): jedna Spring Boot aplikacija, jedna baza, jasno razgraničeni
moduli. Svaki modul je Java paket `com.tapin.<modul>` i ima parnjak u `frontend/src/features/<feature>`.

## Pregled

| Modul (backend) | Odgovornost | Tablice (vlasništvo) | API (`/api/v1`) | Frontend feature | Faza |
|---|---|---|---|---|---|
| `shared` | Greške (`problem+json`), OpenAPI, sat/vremenska zona, raspoređivanje, zajednički API tipovi | — | `/system/**` | `lib/`, `components/` | 0 |
| `auth` | Spring Security konfiguracija, login/logout (sesija, CSRF), autorizacija endpointa, pozivnice, reset lozinke, hash lozinki i tokena | `users`, `invitations`, `password_reset_tokens` | `/auth/**`, `/me` | `features/auth` | 1 |
| `members` | Klub, članstvo, uloge, status članstva, profil | `clubs`, `club_memberships`, `membership_roles`, `membership_status_events` | `/members/**` | `features/members` | 1 |
| `scheduling` | Ponavljajuća pravila, materijalizacija termina, izmjene/otkazivanja, treneri termina | `recurring_schedules`, `training_sessions`, `session_coaches`, `training_session_events` | `/trainings/**`, `/recurring-schedules/**` | `features/trainings` | 2 |
| `registrations` | Prijava/otkazivanje prijave člana | `training_registrations`, `registration_events` | `/trainings/{id}/my-registration` | `features/trainings` | 2 |
| `attendance` | **Poslovna jezgra**: PRESENT/ABSENT, masovno označavanje, optimistično zaključavanje, audit | `training_attendances`, `attendance_audit_logs` | `/trainings/{id}/roster`, `/trainings/{id}/attendance/**` | `features/attendance` | 3 |
| `belts` | Povijest pojaseva, promocije, ispravci uz audit | `member_belt_periods`, `belt_period_audit_logs` | `/members/{id}/belts/**`, `/me/belts` | `features/belts` | 4 |
| `notifications` | Transactional outbox, worker, retry/backoff, deduplikacija, podsjetnici, HR predlošci | `email_outbox` | — (interno) | — | 5 |
| `reporting` | Statistika, izvještaji, CSV izvoz — **samo čitanje** | — (samo pogledi `R__reporting_*`) | `/reports/**`, `/me/statistics`, `/members/{id}/statistics` | `features/statistics` | 6 |

Trenutačni vlasnik svih modula je `@simovic1` (`.github/CODEOWNERS`). Kad tim naraste, vlasnik se mijenja po modulu
u CODEOWNERS-u i u ovoj tablici.

## Pravila vlasništva

1. **Samo vlasnik piše u svoje tablice.** Drugi moduli čitaju/mijenjaju podatke isključivo kroz javni servis
   vlasnika (npr. `registrations` ne upisuje u `training_attendances`, nego pita `attendance` za stanje).
2. **`AttendanceService` je jedini autoritet** za upis prisutnosti i audit (§13.2). Prije svakog upisa uzima
   `registrations.ParticipationLock` za (termin, član) – istu bravu drži i samostalna prijava/otkaz člana – pa se
   otkaz prijave i evidencija istog člana nikad ne isprepliću (AC-05). `registrations` stanje prisutnosti čita kroz
   SPI `AttendanceStatusLookup` koji implementira `attendance`.
   `belts` broji treninge po pojasu iz `AttendanceService.presentSessionIds` i `TrainingService.heldLocalDates`
   (izračun pri svakom čitanju, bez pohranjenih brojača); trenutačni pojas za imenik i prozivku daje kroz SPI
   `members.CurrentBeltLookup`, pa `members`/`attendance` ne ovise o `belts`.
3. **`reporting` ne posjeduje tablice** – koristi read-only SQL upite i repeatable poglede (`R__reporting_*`).
   Brojači se ne pohranjuju kao poslovna istina (§9.3).
4. **`notifications`**: ostali moduli upisuju događaj u outbox *u istoj transakciji* kao poslovnu promjenu, preko
   API-ja modula `notifications`; slanje radi pozadinski worker (§8.3).
5. **Smjer ovisnosti** (bez ciklusa):

   ```text
   shared  <-  auth  <-  members  <-  scheduling  <-  registrations  <-  attendance
                              ^            ^                                  |
                              +-- belts ---+                                  |
   notifications  <- (svi poslovni moduli upisuju događaje)                   |
   reporting      -> (čita sve, nitko ne ovisi o njemu)  <---------------------+
   ```

   `shared` ne ovisi ni o jednom poslovnom modulu. `auth` ne ovisi o `members`: uloge i članstvo dobiva preko
   SPI sučelja `MembershipAuthorityLookup` koje implementira `members`.
6. **Javno vs. interno unutar modula**: kontroleri, DTO-i i servis koji drugi moduli koriste su `public`;
   entiteti, repozitoriji i pomoćne klase su package-private gdje god je moguće. Drugi moduli ne importaju
   JPA entitete tuđeg modula — razmjenjuju ID-jeve i DTO/record tipove.

## Struktura paketa unutar modula

```text
com.tapin.attendance/
  package-info.java          # opis modula, tablice, API, dopuštene ovisnosti
  AttendanceController.java  # REST, bez poslovne logike
  AttendanceService.java     # poslovna pravila + transakcijske granice (public API modula)
  TrainingAttendance.java    # JPA entitet (package-private)
  TrainingAttendanceRepository.java
  dto/                       # request/response record tipovi
```

Kad modul preraste ~15 klasa, dijeli se na pod-pakete `api/` (kontroleri, DTO), `domain/` (entiteti, servisi) i
`infra/` (repozitoriji, integracije).
