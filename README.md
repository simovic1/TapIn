# TapIn – evidencija BJJ treninga

Web aplikacija za BJJ klub: članovi se prijavljuju na treninge, treneri evidentiraju stvarne dolaske, sustav vodi
statistiku, povijest pojaseva i šalje e-mail obavijesti.

Specifikacija: [`docs/bjj_evidencija_specifikacija_mvp_v1_1.md`](docs/bjj_evidencija_specifikacija_mvp_v1_1.md).
Stanje: **Faza 0 – temelj projekta** (skeleton, infrastruktura, konvencije).

## Stack

| Dio | Tehnologija (zaključane verzije) |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1 (MVC, Security, Data JPA, Validation, Mail, Actuator), Flyway, springdoc-openapi 3.1.1, Maven 3.9.16 (wrapper) |
| Frontend | React 19.3.1, TypeScript 6.0.3, Vite 8.3.4, React Router 7.18.4, TanStack Query 5.104.3, Tailwind CSS 4.3.3 |
| Baza | PostgreSQL 18.6 |
| Lokalni e-mail | Mailpit 1.31.6 |
| Testovi | JUnit 5, Spring Boot Test, Testcontainers 2 (PostgreSQL) |

## Struktura monorepa

```text
TapIn/
├── backend/                     Spring Boot modularni monolit
│   ├── mvnw, mvnw.cmd, .mvn/    Maven wrapper (nije potreban instalirani Maven)
│   └── src/main/
│       ├── java/com/tapin/
│       │   ├── shared/          security, greške (problem+json), OpenAPI, vrijeme
│       │   ├── auth/  members/  belts/  scheduling/
│       │   ├── registrations/  attendance/  reporting/  notifications/
│       └── resources/
│           ├── application.yml            zajednička konfiguracija (bez tajni)
│           ├── application-local.yml      lokalni razvoj
│           ├── application-staging.yml
│           ├── application-prod.yml
│           └── db/migration/              Flyway migracije
├── frontend/                    React + Vite SPA
│   └── src/
│       ├── app/                 providers, router, query client
│       ├── components/          layout i zajednički UI
│       ├── lib/                 API klijent (session + CSRF), formatiranje
│       ├── pages/
│       └── features/            auth, trainings, attendance, members, belts, statistics
├── docs/
│   ├── bjj_evidencija_specifikacija_mvp_v1_1.md
│   └── architecture/            moduli i vlasništvo, migracije, konvencije
├── scripts/check-migrations.sh  provjera naziva i redoslijeda Flyway migracija
├── docker-compose.yml           PostgreSQL + Mailpit (+ opcionalno app kontejneri)
├── .env.example                 primjer lokalne konfiguracije
└── .github/                     CI i CODEOWNERS
```

## Preduvjeti

- **Docker** (Docker Desktop ili Docker Engine s Compose v2)
- **JDK 21** (npr. Temurin ili Corretto)
- **Node.js 24 LTS** (`frontend/.nvmrc`; npm dolazi uz Node)

Maven nije potrebno instalirati – koristi se wrapper.

## Pokretanje lokalno

Sve naredbe iz korijena repozitorija. Nije potrebno mijenjati kod ni konfiguraciju.

### 1. Baza i e-mail

```bash
cp .env.example .env        # opcionalno – zadane vrijednosti već odgovaraju lokalnom profilu
docker compose up -d
```

PostgreSQL je na `localhost:5432` (baza/korisnik/lozinka: `tapin`), Mailpit UI na <http://localhost:8025>.

### 2. Backend

```bash
cd backend
./mvnw spring-boot:run          # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

`spring-boot:run` automatski koristi profil **`local`**. Flyway pri startu primjenjuje migracije.

- API: <http://localhost:8080/api/v1/system/info>
- Health: <http://localhost:8080/actuator/health>
- Swagger UI: <http://localhost:8080/swagger-ui.html>

Alternativa bez docker-compose baze (Testcontainers podiže privremeni PostgreSQL):
`./mvnw spring-boot:test-run`.

### 3. Frontend

```bash
cd frontend
npm ci
npm run dev
```

Aplikacija: <http://localhost:5173>. Vite proxy prosljeđuje `/api`, `/actuator` i Swagger na backend (`:8080`),
pa su SPA i API na istom originu – session i CSRF cookieji rade kao u produkciji. Početna stranica prikazuje
„Stanje sustava” čim je backend dostupan.

### Sve u Dockeru (opcionalno)

```bash
docker compose --profile app up -d --build
```

Frontend (nginx + proxy na backend): <http://localhost:3000>.

## Profili i tajne

| Profil | Namjena | Izvor konfiguracije |
|---|---|---|
| `local` | razvoj na vlastitom računalu | `application-local.yml` – lokalne, ne-tajne vrijednosti usklađene s `docker-compose.yml` |
| `test` | automatski testovi | `src/test/resources/application-test.yml` + Testcontainers |
| `staging` | testno okruženje | varijable okruženja (obvezne) |
| `prod` | produkcija | varijable okruženja (obvezne); Swagger isključen, `flyway.clean` zabranjen |

- U repozitoriju **nema tajni**. `application.yml` referencira `${DB_URL}`, `${DB_PASSWORD}`, `${MAIL_*}` itd.
  bez zadanih vrijednosti, pa staging/prod ne može startati bez ispravno postavljenog okruženja.
- `.env` i `frontend/.env.local` su u `.gitignore`; primjeri su `.env.example` i `frontend/.env.example`.
- Popis varijabli potrebnih za staging/prod nalazi se na dnu `.env.example`.
- Pokretanje jar-a s profilom: `SPRING_PROFILES_ACTIVE=prod java -jar backend/target/tapin-backend-*.jar`.

## Testovi i provjere

```bash
cd backend && ./mvnw verify           # unit + slice + integracijski (Testcontainers; preskače se bez Dockera)
cd frontend && npm run build          # typecheck + produkcijski build
sh scripts/check-migrations.sh        # naziv i redoslijed Flyway migracija
```

CI (`.github/workflows/ci.yml`) pokreće sve tri provjere na svaki push i pull request.

## Konvencije

- [Moduli i vlasništvo](docs/architecture/modules.md) – odgovornosti, tablice, API i smjer ovisnosti po modulu
- [Flyway migracije](docs/architecture/database-migrations.md) – imenovanje, dogovoreni redoslijed, SQL konvencije
- [Konvencije koda](docs/architecture/conventions.md) – imenovanje u backendu, API-ju, frontendu i gitu

## Licenca

[MIT](LICENSE)
