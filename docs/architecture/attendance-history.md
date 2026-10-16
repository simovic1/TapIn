# Povijesna evidencija i revizijska povijest (BJJ-22/23)

Trener ili administrator otvara **Prozivka → Dodaj trening**, označi **Povijesni trening** i unese datum/vrijeme u zoni Europe/Zagreb. Završeni ručni termin nastaje kao HELD. Iz detalja termina otvara prozivku. Stariji postojeći termin dostupan je kroz kalendar.

Prozivka unaprijed upozorava da svaka stvarna izmjena šalje obavijest. U povijesnoj pretrazi dostupni su i sada neaktivni članovi. Backend za novi povijesni zapis provjerava membership_status_events na početak treninga, uključujući joined_on, pa unos izvan aktivnog razdoblja nije dopušten. Za nezavršeni termin i dalje vrijedi današnja aktivnost; HISTORICAL_IMPORT ne zaobilazi provjeru.

Napomena (do 500 znakova) šalje se uz upis i poništavanje. Backend novi završeni unos označava HISTORICAL_IMPORT, promjenu postojećeg statusa i povratak na UNMARKED kao CORRECTION. Masovno označavanje zadržava BULK i također provjerava aktivnost na datum starog treninga. Audit i outbox nastaju u istoj transakciji; identičan status ne šalje novu obavijest. expectedVersion i sačuvani UNMARKED redak štite naknadne ispravke.

Evidencija je vezana uz session_id i datum training_sessions.start_at u Europe/Zagreb. occurred_at u auditu označava vrijeme izmjene, a ne statističko razdoblje. Modul statistike još nije implementiran: njegovi budući izračuni moraju koristiti datum treninga, ne datum audita.

**Prikaži povijest** otvara audit od najnovije promjene, s filtrom člana, stranicama od 20 zapisa i ručnim osvježavanjem. Prikazuje prije/poslije, sve izvore, napomenu, lokalno vrijeme i autora. Imena su trenutačna prikazna imena; actorUserId je trajni identitet autora. UI nema uređivanje/brisanje audit redaka. Endpoint i prozivka dostupni su isključivo TRAINER/ADMIN; audit tablica ostaje zaštićena od UPDATE/DELETE.

Automatizirani uvoz datoteka nije dio ovog toka.
