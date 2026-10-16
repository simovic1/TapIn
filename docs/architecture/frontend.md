# Frontend osnova

React 19 + TypeScript + Vite, React Router 7, TanStack Query 5, Tailwind CSS 4. Mobile-first (§12.4).

## Struktura

```text
frontend/src/
  app/            AppProviders, router (sve rute), navigation.ts (stavke + uloge), queryClient
  components/
    layout/       AppLayout (zaglavlje, donja traka na mobitelu), MoreMenu ("Više"), AuthLayout
    ui/           Button, TextField, Alert, LoadingState, ErrorState, EmptyState, QueryState, PageHeader, Icon
  lib/
    api/          apiFetch (sesija + CSRF + problem+json), schema.d.ts (generirano iz ugovora), errors.ts
    session/      useCurrentUser/useLogin/useLogout, guards (RequireAuth, RequireRole), roles, istek sesije
  pages/          početna, 403, 404
  features/<f>/   routes.tsx + pages/ po modulu (auth, trainings, attendance, members, belts, statistics)
  test/           setup i pomoćne funkcije za Vitest
```

## Sesija i CSRF

- Svi pozivi idu kroz `apiFetch` (`lib/api/client.ts`): isti origin, `credentials: same-origin`, a za
  POST/PUT/PATCH/DELETE šalje `X-XSRF-TOKEN` iz cookieja `XSRF-TOKEN`. Greške postaju `ApiError` s `problem`
  (`errorCode`, `detail`, `fieldErrors`, ...).
- `useCurrentUser()` čita `/me`; 401 znači "nitko nije prijavljen" (`null`), ne grešku.
- **Istek sesije:** `QueryCache`/`MutationCache` hvataju svaki 401 (`handleUnauthorized`). Ako je korisnik bio
  prijavljen, briše se predmemorija, označava istek, a `RequireAuth` vodi na `/prijava` s porukom
  "Sesija je istekla" i vraća korisnika na izvornu stranicu nakon prijave (samo relativne putanje).
  Pogrešna lozinka (`INVALID_CREDENTIALS`) se ne tretira kao istek.
- Prijava/odjava čiste cijelu predmemoriju kako podaci prethodnog korisnika ne bi procurili.

## Aktivacija i oporavak lozinke (BJJ-09)

- `/pozivnica#token` prikazuje podatke pozivnice, provjerava lozinku i nakon aktivacije vodi na prijavljenu početnu stranicu.
- `/zaboravljena-lozinka` šalje e-mail kroz zajednički API klijent i prikazuje istu potvrdu neovisno o postojanju računa.
- `/nova-lozinka#token` šalje `token` i `newPassword`, provjerava duljinu i potvrdu lozinke te nakon uspjeha čisti
  predmemoriju sesije i nudi novu prijavu. Token ostaje u fragmentu URL-a, izvan access logova.
- Obrasci onemogućuju slanje dok zahtjev traje i prikazuju razumljive poruke za nevaljanu/isteklu poveznicu,
  neispravnu lozinku, `429` i nedostupan poslužitelj. Prije slanja pribavlja se CSRF cookie preko `/me`.

## Raspored i treninzi (BJJ-15, BJJ-16)

- Sva vremena dolaze u UTC i prikazuju se u `Europe/Zagreb` (`lib/format.ts`: `formatLongDate`, `formatTimeRange`,
  `toClubParts`); unos datuma i vremena u obrascima pretvara se u UTC s `clubDateTimeToIso` (DST-ispravno, bez
  biblioteka). Grupiranje po danu koristi `localDate` s poslužitelja.
- Početna (`/`) prikazuje sljedeći i drugi sljedeći neotkazani trening te otkazane termine prije njih; `/treninzi` je
  kalendar po mjesecima (`?mjesec=YYYY-MM`, jedan upit `[1. u mjesecu 00:00, 1. sljedećeg 00:00)` po zagrebačkom
  vremenu); `/treninzi/:id` je detalj. Otkazani termini imaju tekstualnu oznaku „Otkazan”, precrtano vrijeme i razlog.
- Član nikad ne vidi tko je prijavljen; `registeredCount` dolazi i prikazuje se samo trenerima/administratorima.
- Prijava (BJJ-18): `RegistrationControl` na početnoj (jedan dodir: „Prijavi se” / „Otkaži prijavu”) i na detalju.
  Gumbe nudi samo kad poslužitelj kaže `canRegister`/`canCancelRegistration`; prijava i stvarni dolazak prikazuju se
  odvojeno, a nakon evidencije (`PRESENT`/`ABSENT`) samostalne promjene nisu ponuđene. Uspjeh se prikazuje tek nakon
  odgovora poslužitelja; `409` osvježava prikaz, mrežna greška ostavlja stanje nepromijenjeno. Članovi bez uloge
  MEMBER (samo trener/admin) ne vide prijavu.
- Trener/admin (BJJ-16): `/treninzi/novi` (dodatni ili povijesni trening), `/treninzi/:id/uredi` (**samo taj
  termin**; za termin iz pravila jasno piše da ostali termini ostaju), otkazivanje na detalju uz prikaz što se
  otkazuje (datum, vrijeme, broj prijavljenih, „tjedni raspored ostaje”) i `/treninzi/raspored` (tjedna pravila: izmjena
  vrijedi za buduće termine; prije spremanja pregled koji termini dobivaju novo vrijeme, a koji se otkazuju).
- Termin koji je započeo ili je `HELD` ne može se pomaknuti ni otkazati (odluka D-03): polja vremena su zaključana.
- Spremanje: klijentska validacija (završetak nakon početka, ispravni datumi), zatim greške poslužitelja po poljima
  (`fieldErrors`, `TRAINING_OVERLAP` uz vrijeme početka, `VERSION_CONFLICT` s „Učitaj najnovije”). Uspjeh se
  prikazuje tek nakon odgovora poslužitelja (`mutateAsync`, otporno na ponovno montiranje obrasca), a svaka uspješna
  promjena invalidira cijeli `trainingKeys.all` pa se kalendar osvježi.

## Prozivka (`features/attendance`)

- `/prozivka` predlaže trening (u tijeku → završen u zadnjih 6 h → sljedeći) i nudi ostale iz ±7 dana;
  `/prozivka/:id` je roster: brojači (prijavljeni, prisutni, odsutni, nepotvrđeni), redak po članu s velikim gumbima
  „Prisutan”/„Odsutan” (`aria-pressed`) i „Poništi oznaku”, dodavanje neprijavljenog člana i masovna radnja.
- Svaki dodir sprema odmah (bez gumba „Spremi”) s `expectedVersion` retka. Redak sam pokazuje „Spremanje…”,
  „Spremljeno” ili „Greška pri spremanju” s „Pokušaj ponovno”; promjena se prikazuje tek nakon potvrde poslužitelja
  i upisuje u predmemoriju rostera (brojači se preračunavaju lokalno, bez ponovnog dohvata po dodiru).
- `409 VERSION_CONFLICT`: redak preuzima `currentState` s poslužitelja i jasno kaže što je drugi trener upisao;
  trener odlučuje ponovno (nikad tiho prepisivanje).
- Masovno „odsutni” traži potvrdu s popisom kandidata (REGISTERED + nije označeno) i šalje `expectedMembershipIds`;
  rezultat prikazuje stvarni `updatedCount`. Walk-in i već označeni ostaju vidljivi u rosteru i brojačima.
- Dodavanje člana koristi `GET /members?q=…&membershipStatus=ACTIVE` (BJJ-10) i `PUT …/attendance/{id}` s verzijom 0.
- Na uskom ekranu (375 px) redak je ime + dva gumba punom širinom; bez horizontalnog pomicanja.

## Pojasevi (`features/belts`, BJJ-26)

- `/clanovi/:id/pojasevi` (trener/admin; poveznica iz detalja člana): trenutačni pojas, datum dobivanja, treninzi u
  pojasu i ukupno, razdoblja s brojem treninga, „neraspoređeni” te povijest ispravaka.
- Obrazac razdvaja **datum dodjele** (stvarni datum polaganja) od **početka brojanja** i prije spremanja prikazuje
  početak brojanja: „nakon treninga” = sljedeći dan, „prije treninga” = isti dan. Stariji pojas bez poznatog datuma
  unosi se s „Datum dodjele nije poznat” + „Treninzi se broje od”; datum dodjele ostaje „datum nije evidentiran”.
- Ispravak traži razlog (audit). `409 VERSION_CONFLICT` otvara obrazac sa svježim stanjem iz `currentState` i traži
  novu odluku; uspjeh se prikazuje tek nakon odgovora i osvježava povijest (iz odgovora), audit i prikaze članova.
- `/profil` prikazuje vlastitu povijest pojaseva samo za čitanje (`/me/belts`).

## Navigacija i ovlasti

- `app/navigation.ts` je jedini izvor stavki izbornika; svaka stavka ima `roles`. Korisnik s više uloga dobiva
  **jednu** navigaciju = unija stavki svih uloga (§12.3).
- Rute koriste iste skupove uloga (`STAFF_ROLES`, `ADMIN_ROLES` iz `lib/session/roles.ts`) kroz `RequireRole`;
  bez ovlasti prikazuje se stranica 403. Backend ovlasti provjerava neovisno.
- Desktop: vodoravni izbornik u zaglavlju. Mobitel: donja traka s do 4 primarne stavke + "Više" (ostatak i odjava).

## Pristupačnost

- Skip link "Preskoči na sadržaj"; nakon navigacije fokus prelazi na `<main>`; svaka stranica ima jedan `h1`
  (`PageHeader`) i postavlja naslov kartice.
- Vidljiv fokus (`:focus-visible`) na svim kontrolama; dodirne površine ≥ 44 px (`min-h-11`), donja traka 56 px.
- `TextField`: vidljiva oznaka povezana s poljem, `aria-describedby` za uputu i grešku, `aria-invalid`.
- `LoadingState` = `role="status"`, `ErrorState`/greške obrazaca = `role="alert"`; aktivna stavka `aria-current="page"`.
- "Više" izbornik: `aria-expanded`/`aria-controls`, fokus na prvu stavku, Escape zatvara i vraća fokus.

## Stanja podataka

`QueryState` prikazuje točno jedno stanje upita: učitavanje → greška (s "Pokušaj ponovno") → prazno → podaci.
Mutacije se nikad automatski ne ponavljaju (`retry: false`).

## Testovi

```bash
npm test          # Vitest + Testing Library (jsdom)
npm run test:watch
```

Testovi pokrivaju filtriranje navigacije po ulogama, CSRF zaglavlje i problem+json u `apiFetch`, prijavu,
pogrešne vjerodajnice, istek sesije, 403 rutu, odjavu te pristupačnost osnovnih komponenti.

Prijava radi protiv pravog backenda (lokalno `admin@tapin.local` / `local-dev-admin-password`).

## Rad bez backenda

`npm --prefix api run mock` + `npm run dev:mock`. Vite proxy u mock načinu postavlja `XSRF-TOKEN` i, nakon
`POST /auth/login`, `TAPIN_SESSION` cookie, pa se cijeli tok prijave/odjave može isprobati. Uloge mock korisnika
mijenjaju se zaglavljem `Prefer: example=member|admin` (zadano: trener + član).
