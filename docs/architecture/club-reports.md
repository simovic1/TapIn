# Klupski izvještaji i CSV (BJJ-33/34)

GET /api/v1/reports/attendance i /attendance.csv zahtijevaju from i to kao lokalne datume Europe/Zagreb. Interval je [from,to), najviše 366 dana. Oba koriste isti servis, filtre membershipId/belt i sort; CSV izvozi sve rezultate bez paginacije. Samo TRAINER/ADMIN imaju pristup. Izvještaj se računa u read-only REPEATABLE_READ transakciji. Dva odvojena zahtjeva mogu vidjeti promjene napravljene između njih.

## D-05: pojas i razdoblje

Pojas je **trenutačni pojas člana** (posljednji zapis razdoblja pojasa, jednako profilu). Filtar odabire članove, ne ograničava njihove dolaske na povijesni pojas. UNKNOWN uključuje članove bez pojasa. Dolasci su samo PRESENT na HELD terminima; otkazani i SCHEDULED termini ne ulaze. Datum audita/unosa nije datum statistike.

presentCount vrijedi za odabrani interval. presentThisMonth, presentLast7Days i presentLast30Days njegov su presjek s tekućim mjesecom i zadnjih 7/30 lokalnih dana, uključujući danas. lastPresentOn je posljednji HELD/PRESENT iz cijele povijesti i ne ovisi o intervalu.

## Nazivnici

Prosjek = broj dolazaka odabranih članova / broj svih održanih termina u odabranom intervalu, uključujući termine bez dolazaka. Ista formula vrijedi po mjesecu. Prikaz po terminu pokazuje broj dolazaka tih članova. Prazni mjeseci imaju nula termina i null prosjek.

Nedolasci = REGISTERED+ABSENT / REGISTERED+(PRESENT ili ABSENT). Otkazane prijave i UNMARKED nisu u nazivniku. UNMARKED se prikazuje zasebno. Walk-in i dolazak nakon otkazane prijave računaju se u dolaske, ali ne u postotak nedolazaka nakon prijave. Nulti nazivnik daje null. UI i CSV prikazuju broj evidentiranih prijava uz postotak.

## Dulje odsutni

Zaseban pregled /reports/inactive-members koristi cijelu povijest i današnji datum. Filtri glavnog izvještaja ne utječu na njega, što UI izričito navodi. Prikazuju se samo ACTIVE članstva s najmanje N kalendarskih dana od zadnjeg dolaska (UI 14/30). Ako član nikad nije došao, prag se računa od joinedOn, a polja zadnjeg dolaska i proteklih dana ostaju null; UI prikazuje „Nikad nije dolazio”. Novog člana ne označavamo odsutnim prije isteka praga.

## CSV

UTF-8 BOM, separator ;, CRLF, decimalni zarez, datumi dd.MM.yyyy. Tekstualna polja uvijek su citirana, navodnici udvostručeni. Tekst koji nakon početnih razmaka, Unicode separatora ili kontrolnih znakova počinje s =,+,-,@ dobiva vodeći apostrof protiv spreadsheet formula. Brojčana polja nastaju iz izračuna, ne iz korisničkog teksta. CSV sadrži i nazivnik nedolazaka te broj nepotvrđenih prijava. Izvoz koristi sesiju i zajedničko rukovanje API greškama; neuspješan zahtjev ne stvara datoteku.

U UI-ju: Izvještaji → odaberi datume, pojas i/ili člana → odaberi redoslijed → Preuzmi CSV. Promjena filtra vraća paginaciju na prvu stranicu. Klik na ime otvara profil člana.
