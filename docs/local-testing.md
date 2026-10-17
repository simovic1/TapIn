# Ručno testiranje uloga trenera i člana (lokalno)

## 1. Testni podaci

```bash
sh scripts/dev-seed.sh
```

Skripta (`scripts/dev-seed.sql`) dodaje u lokalnu bazu:

| Račun | Uloga | Namjena |
|---|---|---|
| `trener@bd.test` | član + trener | prozivka, raspored, članovi, izvještaji |
| `clan01@bd.test` … `clan10@bd.test` | član | prijavljeni na današnji trening |
| `clan11@bd.test`, `clan12@bd.test` | član | nisu prijavljeni – za „dodaj člana” na prozivci |

Lozinka za sve: **`trening-lokalno`**.

Treninzi: **danas** (počeo prije 20 min, traje 90 min, 10 prijavljenih, nitko nije označen), **sutra u 18:30**
(4 prijavljena) i **6 održanih** u zadnja tri tjedna s mješovitim dolascima (statistika, pojasevi, povijest).

Datum se ne mora „mockati”: današnji trening se stvara relativno na trenutačno vrijeme. Skriptu možete pokrenuti
ponovno bilo koji dan – postojeći računi ostaju, a novi „današnji” trening dodaje se samo ako je termin slobodan.
Nakon kraja treninga aplikacija ga sama označi kao održan (HELD), pa se mogu testirati i naknadni ispravci.

## 2. E-mail tijekom testiranja

Prozivka šalje e-mail svakom članu. Ako backend šalje preko Gmaila, poruke za `@bd.test` adrese vraćaju se kao
neisporučene u sandučić `bd.samobor@gmail.com`. Za testiranje pokrenite backend s Mailpitom (poruke vidite na
http://localhost:8025):

```bash
MAIL_HOST=mail MAIL_PORT=1025 MAIL_SMTP_AUTH=false MAIL_STARTTLS=false docker compose --profile app up -d backend
```

Povratak na Gmail: `docker compose --profile app up -d backend` (vrijednosti iz `.env`).

## 3. Scenariji

Dva preglednika (ili jedan običan + jedan privatni prozor) da trener i član budu prijavljeni istodobno.
Aplikacija: http://localhost:3000.

**Trener (`trener@bd.test`)**

1. *Prozivka* → današnji „Fundamentals (test)”: označi nekoliko „Prisutan” / „Odsutan”.
2. Dodaj neprijavljenog člana (Nina Šarić) kao prisutnog.
3. „Označi sve preostale odsutnima” – mijenja samo neoznačene.
4. Ispravi jednog člana iz „Odsutan” u „Prisutan”; *Prikaži povijest* pokazuje audit.
5. *Treninzi* → sutrašnji trening: uredi vrijeme ili otkaži (prijavljeni dobiju obavijest).
6. *Članovi* → član → pojasevi: promocija; *Izvještaji*: razdoblje, CSV.

**Član (`clan01@bd.test`)**

1. *Početna*: sutrašnji trening – prijava / otkazivanje.
2. Današnji trening nakon što ga trener označi: otkazivanje više nije ponuđeno.
3. *Statistika*: brojači, postotak s nazivnikom, povijest (uključujući „Čeka evidenciju trenera”).
4. *Moj profil*: pojas i broj treninga u njemu; isključi podsjetnik.

**Istodobne izmjene:** otvorite istu prozivku u dva prozora kao trener i označite istog člana različito – drugi
prozor dobiva upozorenje i osvježen redak (409).
