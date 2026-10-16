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
- Prijava na trening dolazi u BJJ-18 (`myRegistrationStatus` se zasad samo prikazuje).

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
