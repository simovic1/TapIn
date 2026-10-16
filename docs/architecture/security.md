# Autentikacija i autorizacija

Modul `auth` (spec §2–3, §14). Konfiguracija: `com.tapin.auth.SecurityConfig`.

## Sesija

- `POST /api/v1/auth/login` (JSON e-mail + lozinka) → Spring Security sesija u kolačiću `TAPIN_SESSION`.
  - Lozinke: `DelegatingPasswordEncoder` (`{bcrypt}`), hash se ne zadržava u sesiji.
  - Isti odgovor (`401 INVALID_CREDENTIALS`) za nepostojeći e-mail, krivu lozinku, pozvani (`INVITED`) ili
    onemogućeni (`DISABLED`) račun – nema otkrivanja postojanja računa.
  - Prijava mijenja ID sesije (zaštita od session fixation) i odmah izdaje novi CSRF token.
  - Ograničenje: 5 neuspjelih pokušaja po e-mailu / 20 po IP-u u 15 min → `429 RATE_LIMITED` + `Retry-After`
    (`tapin.security.login.*`; u memoriji, dovoljno za jednu instancu).
- `POST /api/v1/auth/logout` → invalidira sesiju, briše `TAPIN_SESSION` i CSRF token; idempotentno (`204`).
- `GET /api/v1/me` → trenutni korisnik (`userId`, `membershipId`, uloge, članstvo, klub).
- **Svaki zahtjev ponovno provjerava korisnika u bazi** (`RefreshingSecurityContextRepository`): deaktiviran
  račun ili uklonjeno članstvo gubi pristup odmah (sljedeći zahtjev = 401), promjena uloga vrijedi bez nove prijave.
- Anonimni zahtjevi ne stvaraju sesiju (request cache isključen; SPA sama pamti povratnu stranicu).

## Pozivnice i aktivacija (BJJ-07)

- `POST /members/invitations` (TRAINER/ADMIN): u **jednoj transakciji** nastaju račun `INVITED` (bez lozinke),
  članstvo `ACTIVE` sa zadanim ulogama, pozivnica i e-mail u outboxu. Trener smije zadati samo `MEMBER`
  (`RoleAssignmentPolicy`, `403 ROLE_ASSIGNMENT_FORBIDDEN`). Postojeći e-mail (bez obzira na velika/mala slova) →
  `409 DUPLICATE_RESOURCE`, ništa se ne stvara (provjera + jedinstveni indeks za istodobne zahtjeve).
- **Token:** 256 nasumičnih bita (base64url); u bazi samo SHA-256 hash. Vrijedi 7 dana
  (`tapin.invitation.validity`).
- **Poveznica:** `{APP_BASE_URL}/pozivnica#{token}`. Token je u fragmentu URL-a, pa ga preglednik ne šalje
  poslužitelju ni proxyju (ne završava u access logovima ni `Referer`-u); SPA ga šalje u tijelu POST zahtjeva.
  Payload outbox retka (s poveznicom) briše se nakon slanja.
- `POST /auth/invitations/preview` i `POST /auth/invitations/accept` (javno, uz CSRF): nepostojeći, istekao,
  iskorišten ili poništen token daju isti `422 INVITATION_INVALID_OR_EXPIRED`; 20 neispravnih tokena s iste IP adrese
  u 15 min → `429`.
- **Aktivacija** je jednokratna i atomična (uvjetni `UPDATE ... WHERE accepted_at IS NULL AND revoked_at IS NULL AND
  expires_at > now`), pa ni istodobni zahtjevi ne mogu iskoristiti token dvaput. Postavlja lozinku (min. 10 znakova,
  max. 72 bajta zbog bcrypta), račun prelazi u `ACTIVE` i korisnik je odmah prijavljen (nova sesija + CSRF token).
- **Ponovno slanje** (`POST /members/{membershipId}/invitations/resend`): poništava (`revoked_at`) sve otvorene
  pozivnice korisnika – stara poveznica odmah prestaje vrijediti – i šalje novu. Samo za račune `INVITED`, inače `422`.
- Status računa (`INVITED` → `ACTIVE`, `DISABLED`) i status članstva (`ACTIVE`/`INACTIVE`) su odvojeni: pozvani član
  ima aktivno članstvo, ali se ne može prijaviti dok ne aktivira račun.

## Reset lozinke i ograničenja (BJJ-08)

- `POST /auth/password-reset/request` s poljem `email` vraća prazan `202` za poznat, nepoznat,
  pozvan ili onemogućen račun. Samo aktivan račun dobiva e-mail preko transactional outboxa.
- Token ima 256 nasumičnih bita; tablica `password_reset_tokens` sadrži samo SHA-256 hash.
  Vrijedi **30 minuta**. Novi zahtjev poništava prethodne tokene istog računa.
- Poveznica je `/nova-lozinka#<token>`; fragment se ne šalje proxyju. Token i lozinka šalju se samo u
  tijelu POST zahtjeva, ne logiraju se; tajni outbox payload briše se po završetku slanja.
- `POST /auth/password-reset/confirm` prima `token` i `newPassword`. Potvrda je jednokratna i
  transakcijska, uključujući istodobne zahtjeve. Istekao, iskorišten ili poništen token daje isti
  `422 PASSWORD_RESET_TOKEN_INVALID`. Lozinka slijedi istu politiku kao aktivacija (10 znakova, 72 UTF-8 bajta).
- Uspjeh vraća `204`, povećava `users.credentials_version` i poništava ostale reset tokene.
  **Sve prethodne sesije gube pristup pri sljedećem zahtjevu**, na svim instancama koje koriste istu bazu.
  Reset ne prijavljuje korisnika automatski; potrebna je nova prijava.
- Za zahtjev i potvrdu reseta zasebno: najviše 5 pokušaja po cilju i 20 po IP-u u 15 minuta.
  Cilj je normalizirani e-mail za zahtjev, hash tokena za potvrdu. I nepoznate adrese troše isti limit.
  Izdavanje i ponovno slanje pozivnica dijele limit 5 po operateru i 20 po IP-u u 15 minuta.
  Prekoračenje vraća `429 RATE_LIMITED` i `Retry-After`; CSRF ostaje obvezan na svim endpointima.
  Limiteri su u memoriji za jednu MVP instancu; za više instanci potreban je zajednički store.

## Kolačići

| Kolačić | HttpOnly | Secure | SameSite | Napomena |
|---|---|---|---|---|
| `TAPIN_SESSION` | da | da (osim `local` profila; `SESSION_COOKIE_SECURE` u composeu) | Lax | sesija |
| `XSRF-TOKEN` | ne (SPA ga čita) | da kad je zahtjev HTTPS (izravno ili `X-Forwarded-Proto: https`) | Lax | CSRF token |

Iza reverse proxyja backend vjeruje `X-Forwarded-*` zaglavljima (`server.forward-headers-strategy: framework`),
pa proxy mora ta zaglavlja postavljati (Caddy/nginx) i backend ne smije biti izravno dostupan izvana.

## CSRF

Double-submit cookie: svaki POST/PUT/PATCH/DELETE (uključujući prijavu i odjavu) mora poslati zaglavlje
`X-XSRF-TOKEN` jednako kolačiću `XSRF-TOKEN`. Token se postavlja već na prvom GET zahtjevu, a nakon prijave se
rotira. Neispravan/nedostajući token → `403 CSRF_TOKEN_INVALID`.

## Autorizacija

1. **Endpoint pravila** (`ApiAuthorizationRules`) – za svaku operaciju iz `api/openapi.yaml` prema `x-roles`.
   `ApiAuthorizationContractTest` poziva *svaku* operaciju iz ugovora kao anonimni korisnik i sa 5 kombinacija uloga
   i provjerava 401/403/prolaz, pa je svaki endpoint zaštićen i prije nego što je implementiran. Nepoznate putanje
   izvan `/api/v1` su zatvorene (`denyAll`).
2. **Vlasništvo podataka** – član do svojih podataka dolazi samo preko `/me/...`, gdje se `membershipId` uzima iz
   sesije, nikad iz URL-a. Putanje `/members/{membershipId}/...` dostupne su samo TRAINER/ADMIN, pa promjena ID-a
   u URL-u ne otkriva tuđe podatke (AC-17, AC-29). Servisi koji primaju `membershipId` od člana moraju provjeriti
   `AuthenticatedUser.ownsMembership(id)`.
3. **Poslovna pravila** u servisima, npr. `RoleAssignmentPolicy` (trener poziva samo s ulogom MEMBER, AC-18).

Uloge su aditivne, bez hijerarhije:

| Pravilo | Kako je ostvareno |
|---|---|
| ADMIN ima operativne ovlasti trenera | sva "staff" pravila su `hasAnyRole(TRAINER, ADMIN)` |
| TRAINER/ADMIN bez MEMBER se ne prijavljuje na trening | `/trainings/*/my-registration` zahtijeva `MEMBER` |
| Samo ADMIN mijenja uloge i status članstva | `PATCH /members/*`, `PUT /members/*/roles` → `ADMIN` |
| Trener ne dodjeljuje TRAINER/ADMIN | `RoleAssignmentPolicy` → `403 ROLE_ASSIGNMENT_FORBIDDEN` |

## Provjera u HTTPS okruženju

Automatski: `CookieSecurityOverHttpsTest` (pravi Tomcat, produkcijske postavke kolačića, `X-Forwarded-Proto: https`).

Ručno, s lokalnim TLS proxyjem (Caddy, vlastiti CA):

```bash
SESSION_COOKIE_SECURE=true docker compose --profile app --profile https up -d --build
curl -sk -c jar.txt -b jar.txt -D - -o /dev/null https://localhost:8443/api/v1/me
```

Očekivano: `401` bez `TAPIN_SESSION`, `XSRF-TOKEN=...; Secure; SameSite=Lax`. Nakon prijave
`TAPIN_SESSION=...; Secure; HttpOnly; SameSite=Lax`, a nakon odjave oba kolačića obrisana i stara sesija vraća `401`.
