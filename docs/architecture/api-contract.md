# API ugovor (`/api/v1`)

Ugovor je **contract-first**: [`api/openapi.yaml`](../../api/openapi.yaml) (OpenAPI 3.1) je izvor istine za backend i
frontend. Pokriva sve endpointe iz specifikacije §11, uključujući pozivnice, pojaseve, evidenciju prisutnosti i
CSV izvoz. Opće konvencije (autentikacija, autorizacija, datumi, paginacija, `expectedVersion`, greške) opisane su
u `info.description` samog ugovora i vidljive su u svakom pregledniku OpenAPI-ja.

## Alati (`api/`)

| Naredba (iz `api/`) | Što radi |
|---|---|
| `npm ci` | Instalira zaključane alate (Redocly CLI, Prism, openapi-typescript) |
| `npm run lint` | Validira ugovor (Redocly, pravila u `api/redocly.yaml`) |
| `npm run types` | Generira `frontend/src/lib/api/schema.d.ts` |
| `npm run types:check` | CI provjera da su generirani tipovi u skladu s ugovorom |
| `npm run mock` | Prism mock poslužitelj na `http://127.0.0.1:4010` |

**Promjena ugovora:** uredi `openapi.yaml` → `npm run lint` → `npm run types` → commitaj oba. Ako se mijenja
`ErrorCode`, ažuriraj i `com.tapin.shared.error.ErrorCode` (test `ErrorCodeContractTest` provjerava podudaranje).

## Frontend protiv mocka

```bash
npm --prefix api run mock          # terminal 1
npm --prefix frontend run dev:mock # terminal 2 -> http://localhost:5173
```

U `mock` načinu Vite prosljeđuje `/api/v1/*` na Prism (bez prefiksa, jer Prism poslužuje putanje ugovora).
Prism vraća primjere iz ugovora ili podatke generirane iz shema. Određeni odgovor bira se zaglavljem
`Prefer`, npr. `Prefer: code=409` (konflikt s `currentState`) ili `Prefer: example=historicalUnknownDate`.
Prism vraća `401` ako nema cookieja `TAPIN_SESSION` (u pregledniku ga postavi ručno, npr. `document.cookie =
"TAPIN_SESSION=mock"`). Za neispravno tijelo zahtjeva Prism vraća `422`, dok pravi backend vraća `400`.

## Ključne odluke

- **`userId` vs. `membershipId`** – odvojeni tipovi `UserId` i `MembershipId`. Članske radnje (prijave, prisutnost,
  pojasevi, statistika, uloge) i putanje `/members/{membershipId}` koriste `membershipId`; `userId` označava račun i
  autora izmjene (`actorUserId`, `updatedByUserId`). Polje `memberId` iz primjera u §11.7 namjerno je zamijenjeno
  s `membershipId` (+ `userId` za prikaz).
- **`UNMARKED`** – vrijednost `AttendanceStatus` za nepostojeći zapis prisutnosti; upis prima samo
  `PRESENT`/`ABSENT` (`AttendanceMarkStatus`), a povratak na `UNMARKED` je `DELETE` s `expectedVersion` u upitu.
- **`notificationStatus`** – `QUEUED` (upisano u outbox u istoj transakciji, ne znači dostavu) ili `NOT_REQUIRED`
  (idempotentan ponovljeni zahtjev bez stvarne promjene; uz `changed=false`).
- **`expectedVersion`** – obvezan na svim izmjenama verzioniranih resursa; konflikt → `409 VERSION_CONFLICT` sa
  svježim stanjem u `currentState`.
- **Greške** – `application/problem+json` s `type`, `title`, `status`, `detail`, `instance`, `errorCode`; namjena
  statusa 400/401/403/404/409/422/429 je u tablici u `info.description`.
- **Paginacija** – `page` (od 0), `size` (≤ 100), `sort`; odgovor `{ items, page }`. Ograničeni popisi (raspored u
  intervalu, roster, pravila) nisu paginirani.
- **Datumi** – trenuci u UTC (`date-time`, `Z`), kalendarski datumi lokalni za `Europe/Zagreb` (`date`), vrijeme
  rasporeda `HH:mm`.
- **Dopune specifikacije** (označene `x-spec-addition: true`): `POST /auth/invitations/preview`,
  `GET /me/attendance`, `GET /members/{membershipId}/attendance`, `POST /members/{membershipId}/invitations/resend`.

Runtime dokumentacija implementiranih endpointa (springdoc) dostupna je na `/swagger-ui.html` u `local` i `staging`
profilu; ugovor u `api/openapi.yaml` ima prednost ako se razlikuju.
