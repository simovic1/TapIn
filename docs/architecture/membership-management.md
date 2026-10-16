# Imenik, profil i upravljanje članstvom (BJJ-10–12)

## Korisnički tok

- Trener i administrator otvaraju **Članovi**, pretražuju ime/prezime/e-mail, filtriraju po aktivnosti i ulozi,
  sortiraju i listaju stranice. **Pozovi člana** otvara obrazac; trener šalje samo MEMBER, administrator bira uloge.
- Klik na ime otvara detalj, kontakt, uloge i kronološku povijest aktivnosti. Za INVITED račun postoji ponovno
  slanje pozivnice. Status računa i status članstva prikazuju se zasebno.
- Administrator na detalju mijenja osnovne podatke, datum učlanjenja, aktivnost i uloge. Administracija vodi
  u isti imenik. Greške ne brišu unos; eksplicitno ponovno učitavanje zamjenjuje nespremljene vrijednosti.
- **Moj profil** dostupan je svim prijavljenim korisnicima. Samostalno se mijenja samo telefon; svi ostali
  podaci su read-only. Član nema navigaciju ni pristup tuđem detalju ili imeniku.

## API i validacija

- `GET /members`: `q`, `membershipStatus`, `role`, `page` (od 0), `size` (1–100), `sort` s allowlistom.
  Pretraga ne razlikuje velika i mala slova; `%` i `_` su doslovni znakovi. Stabilan završni kriterij je ID.
- `GET /members/{membershipId}`: detalj, verzija članstva i `statusHistory`.
- `PATCH /members/{membershipId}`: isključivo `expectedVersion`, `firstName`, `lastName`, `email`, `phone`,
  `joinedOn`, `membershipStatus`. Izostavljeno polje ostaje isto; samo telefon može biti `null`.
- `PUT /members/{membershipId}/roles`: zamjena nepraznog skupa uloga uz `expectedVersion`.
- `PATCH /me/profile`: isključivo telefon i verzija korisničkog profila. Ne prihvaća tuđi ID, pojas ili uloge.
- Ime/prezime obvezni, do 100 znakova; e-mail valjan, do 320 znakova i jedinstven bez obzira na case.
  Telefon je opcionalan, inače 6–32 znaka iz skupa znamenki, razmaka, `+`, `(`, `)`, `/`, `-`.
  Datum učlanjenja ne smije biti u budućnosti u Europe/Zagreb; povijesni datum je dopušten.
- Promjena e-maila poništava postojeće aktivacijske i reset tokene. Za pozvani račun treba ponovno poslati
  pozivnicu. Izdavanje poveznice i izmjena adrese zaključavaju isti korisnički redak.

## Konkurentnost i ovlasti

Administratorske promjene dijele PostgreSQL transaction advisory lock s bootstrapom administratora.
Nakon zaključavanja ponovno se provjeravaju uloga operatera i aktualna verzija članstva. Verzijski konflikt
vraća `409 VERSION_CONFLICT` i aktualni detalj. Promjena vlastitog telefona također povećava verziju članstva
kako stari administratorski obrazac ne bi prepisao noviji unos.

Zadnji administrator s ACTIVE računom i ACTIVE članstvom ne može izgubiti ADMIN ili postati INACTIVE
(`422 LAST_ADMIN_REMOVAL`). Pozvani i onemogućeni administratori ne računaju se kao zamjena. Zaključavanje
štiti i istodobnu deaktivaciju jednog i uklanjanje uloge drugog administratora. Uloge u postojećim sesijama
osvježavaju se na svakom zahtjevu. Neaktivnost članstva sama po sebi ne briše račun ni raniju povijest.

## Povijest aktivnosti

- Za novog člana početni ACTIVE događaj počinje na `joinedOn` u 00:00 Europe/Zagreb. Vrijedi i za retroaktivni unos.
- V010 dopunjava početno stanje postojećih članova bez izmjene njihovih ranijih događaja. Ako događaji postoje,
  početno stanje uzima se iz prvog poznatog prijelaza; bez događaja iz zatečenog statusa. Nepoznate ranije
  deaktivacije ne izmišljaju se i treba ih razriješiti pri uvozu podataka.
- Deaktivacija/reaktivacija dodaju događaj s točnim trenutkom i operaterom; ponovno spremanje istog statusa
  ne dodaje događaj. Podaci o članstvu i raniji događaji ostaju sačuvani.
- `MemberDirectoryService.activeAt(membershipId, instant)` daje stanje za buduće attendance/reporting module:
  datum mora biti od učlanjenja nadalje, a posljednji događaj do tog trenutka ACTIVE. Intervali su
  `[aktivacija, deaktivacija)`. Naknadno raniji datum učlanjenja dopunjava početno stanje; kasniji datum
  ograničava izračun bez brisanja događaja.

## Pojasevi i dolasci

Dok pripadajući moduli nisu implementirani, API vraća `currentBelt=UNKNOWN`, datume pojasa i zadnjeg dolaska
kao `null`. Sučelje ima zaseban read-only odjeljak za pojas. Članstvo nema stupac za pojas, a PATCH odbija
nepoznata polja. Integracija će izvoditi ove vrijednosti iz povijesti pojaseva i dolazaka; UI zasad ne nudi
filter po pojasu. API filter za poznati pojas daje prazan rezultat dok nema izvedenih podataka.

## Provjere

`MembershipManagementIntegrationTest` pokriva prava pristupa, validaciju, paginaciju, konflikte, promjene
uloga unutar postojeće sesije, aktivna razdoblja, migraciju starijih podataka, promjenu e-maila i konkurentne
promjene zadnjih administratora. `Members.test.tsx` provjerava pozivanje, pretragu, zaštitu ruta, izmjene
statusa/uloga, ponovno slanje, očuvanje unosa pri grešci i vlastiti telefon.
