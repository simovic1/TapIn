# Osobna statistika i povijest treninga (BJJ-31, odluka D-05)

Modul `reporting` (spec §9.1, §9.3). Ne posjeduje tablice: `StatisticsRepository` računa sve brojeve SQL upitom pri
svakom čitanju, pa se naknadni ispravak dolaska, otkazivanje ili pomicanje termina, povijesni unos i ispravak
promocije vide odmah. Ništa se ne pohranjuje kao zaseban brojač.

| Endpoint | Tko | Sadržaj |
|---|---|---|
| `GET /me/statistics` | svaki prijavljeni korisnik – **uvijek vlastito članstvo iz sesije** | brojači, postoci, pojasevi |
| `GET /me/attendance?from&to&page&size` | isto | povijest treninga |
| `GET /members/{id}/statistics`, `/members/{id}/attendance` | samo TRAINER/ADMIN | isto za bilo kojeg člana |

Član ne može dobiti tuđe brojeve: `/me/...` ne prima `membershipId`, a `/members/{id}/...` vraća 403 članu bez uloge
trenera (i za vlastiti ID).

## Pravila brojanja

- **Trening** = `PRESENT` na terminu u stanju `HELD`. Otkazani (`CANCELLED`) i još neodržani (`SCHEDULED`) termini se
  nikad ne broje. Jedan član na jednom terminu najviše jednom (`UNIQUE (session_id, membership_id)`, upit broji
  `DISTINCT` termine).
- **Nedolazak nakon prijave** (`registeredButAbsent`) = prijava `REGISTERED` + `ABSENT` na održanom terminu.
- **Prijave** (`registrations`) = neotkazane prijave na održane termine po ishodu: `present`, `absent`, `unmarked`.
  Otkazana prijava na koju je član ipak došao broji se kao trening, ali ne kao prijava.
- **Postotak dolazaka** (`attendanceRate`, osnova `HELD_SESSIONS_WHILE_ACTIVE`): nazivnik = održani termini kluba s
  lokalnim datumom ≥ `joined_on` na čijem je početku članstvo bilo aktivno (zadnji `membership_status_events` zapis
  do početka termina je `ACTIVE` – isto pravilo kao za povijesni unos dolaska). Brojnik = `PRESENT` na **tim**
  terminima, pa postotak nikad ne prelazi 100 %. Nazivnik i brojnik vraćaju se uz rezultat.
- **Dolazak po prijavi** (`registrationAttendanceRate`, osnova `REGISTRATIONS`): `present / (present + absent)`.
- **Prazan nazivnik**: `rate = null`, nikad greška.
- Treninzi po pojasu dolaze iz `BeltService.history` (§9.3), pa vrijedi `zbroj po pojasevima + neraspoređeni =
  totalPresent`.

## D-05 – razdoblja i UNMARKED

**Razdoblja** su lokalni kalendarski datumi treninga u Europe/Zagreb (ne datum unosa ili ispravka), uključujući
današnji dan; točni datumi vraćaju se u `windows`:

| Polje | Od | Do |
|---|---|---|
| `presentThisMonth` | 1. dan tekućeg mjeseca | danas |
| `presentLast7Days` | danas − 6 dana | danas |
| `presentLast30Days` | danas − 29 dana | danas |

Npr. u petak 16. 10. 2026.: mjesec od 1. 10., 7 dana od 10. 10., 30 dana od 17. 9.

**UNMARKED** (trener još nije odlučio) nije ni trening ni odsutnost:

- ne ulazi u `totalPresent` ni u `registeredButAbsent`;
- ne ulazi u nazivnik dolaska po prijavi, nego se prikazuje zasebno (`registrations.unmarked`);
- u glavnom postotku održani termin ostaje u nazivniku bez obzira na oznaku – osnova je „termini na koje je član
  mogao doći dok je bio aktivan”, a ne „označeni termini”;
- u povijesti se prikazuje kao `UNMARKED`, a UI ga ne smije prikazati kao potvrđenu odsutnost.

## Povijest

`/me/attendance` vraća termine koji su **počeli**, a na koje se član prijavio (i otkazao) ili na kojima je evidentiran,
od najnovijeg. Otkazani termin se prikazuje (s `trainingStatus=CANCELLED`) ali se ne broji. `from`/`to` su lokalni
datumi, `[from, to)`. Budući termini su u kalendaru, ne u povijesti.

Testovi: `StatisticsIntegrationTest`.
