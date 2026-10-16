# Konvencije koda i imenovanja

## Općenito

- **Jezik koda, identifikatora, tablica i commit poruka:** engleski. **Jezik sučelja i korisničkih poruka:** hrvatski.
- **Vrijeme:** backend i baza rade u UTC (`Instant`, `TIMESTAMPTZ`); poslovni datumi (dan treninga, efektivni datum
  pojasa) računaju se u `Europe/Zagreb` (`tapin.club.time-zone`). Umjesto `Instant.now()` injektira se `Clock`.
- **Verzije ovisnosti su zaključane:** backend preko Spring Boot BOM-a + eksplicitnih verzija u `pom.xml`
  (Maven wrapper zaključava i verziju Mavena); frontend točnim verzijama u `package.json` (`save-exact`) i
  `package-lock.json`. Nadogradnje se rade namjerno, u zasebnom PR-u.

## Backend (Java / Spring)

| Element | Konvencija | Primjer |
|---|---|---|
| Paket modula | `com.tapin.<modul>` | `com.tapin.attendance` |
| REST kontroler | `<Resurs>Controller` | `AttendanceController` |
| Servis | `<Resurs>Service` | `AttendanceService` |
| Repozitorij | `<Entitet>Repository` | `TrainingAttendanceRepository` |
| JPA entitet | imenica u jednini, mapira tablicu u množini | `TrainingAttendance` → `training_attendances` |
| Request DTO | `<Radnja><Resurs>Request` (record) | `SetAttendanceRequest` |
| Response DTO | `<Resurs>Response` (record) | `AttendanceResponse` |
| Iznimke | `ApiException` + `ErrorCode` | `ApiException.conflict(...)` |
| Konfiguracija | `@ConfigurationProperties` record pod prefiksom `tapin.*` | `ClubProperties` |
| Testovi | `<Klasa>Test` (unit/slice), `<Nešto>IntegrationTest` (Testcontainers) | |

Pravila:
- Kontroleri ne sadrže poslovnu logiku; transakcije su na servisima.
- API DTO-i su odvojeni od JPA entiteta.
- Sve pogreške su `application/problem+json` s `type, title, status, detail, instance, errorCode`.
  Novi kodovi grešaka dodaju se u `ErrorCode` s prefiksom modula (npr. `ATTENDANCE_VERSION_CONFLICT`).

## REST API

- Prefiks `/api/v1` (`ApiPaths.V1`), resursi u množini, `kebab-case`: `/recurring-schedules`, `/my-registration`.
- JSON polja `camelCase`; datumi ISO 8601 (`2026-10-20T16:30:00Z`, `2026-10-15`).
- Optimistično zaključavanje kroz `expectedVersion` u tijelu zahtjeva; konflikt → `409`.
- Autentikacija: session cookie `TAPIN_SESSION`; mutacije šalju `X-XSRF-TOKEN` (vrijednost iz `XSRF-TOKEN` cookieja).

## Frontend (React / TypeScript)

| Element | Konvencija | Primjer |
|---|---|---|
| Feature mapa | `src/features/<feature>/` (parnjak backend modula) | `features/attendance` |
| Rute featurea | `routes.tsx` izvozi `<feature>Routes: RouteObject[]` | `attendanceRoutes` |
| Stranica | `pages/<Naziv>Page.tsx` | `AttendancePage.tsx` |
| Komponenta | `PascalCase.tsx`, named export | `RosterRow.tsx` |
| Hook | `useXxx` u `hooks/` ili `api.ts` featurea | `useRoster` |
| API pozivi | `features/<feature>/api.ts`, uvijek preko `apiFetch` | |
| Query ključevi | `<feature>Keys` objekt | `attendanceKeys.roster(id)` |
| URL rute (UI) | hrvatski, `kebab-case` | `/treninzi`, `/prozivka` |

Pravila:
- Server state isključivo kroz TanStack Query; mutacije se **ne** ponavljaju automatski.
- Stilovi kroz Tailwind utility klase; dodirne površine min. 44 px (`min-h-11`) za mobilni prikaz.
- Feature ne importa iz drugog featurea; zajednički kod ide u `src/lib` ili `src/components`.

## Git

- Glavna grana: `master`. Rad na granama `feature/<kratki-opis>`, `fix/<kratki-opis>`.
- Commit poruke u imperativu na engleskom (`Add attendance audit table`).
- PR mora proći CI (migracije, backend `verify`, frontend build).
