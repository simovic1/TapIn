# Render: besplatna proba

Repozitorij sadrži `render.yaml` za jedan besplatni Docker Web Service i besplatnu PostgreSQL bazu u Frankfurtu. `Dockerfile.render` gradi frontend i ugrađuje ga u Spring Boot. Frontend i API dijele HTTPS origin, bez CORS-a i bez zasebnog nginx servisa. Lokalni Docker Compose ostaje nepromijenjen.

## Postavljanje

1. Commitati i pushati konfiguraciju na GitHub, bez `.env` datoteka.
2. Render Dashboard → New → Blueprint → odabrati `simovic1/TapIn` i granu s konfiguracijom.
3. Provjeriti da **oba** resursa imaju plan **Free**. Ako workspace već ima besplatnu bazu, ne stvarati plaćenu zamjenu: prvo odlučiti koristi li se postojeća baza.
4. Unijeti tražene varijable isključivo u Render:
   - `ADMIN_EMAIL`: adresa za prvi administratorski račun.
   - `ADMIN_PASSWORD`: nova jaka lozinka, najmanje 12 znakova. Ne slati je u chat.
   - `VAPID_PUBLIC_KEY` i `VAPID_PRIVATE_KEY`: odgovarajući par iz lokalne `.env`, ili novi par iz `node scripts/generate-vapid-keys.mjs`. Privatni ključ ne commitati.
   - `VAPID_SUBJECT`: `mailto:tvoja-adresa@example.com`, sa stvarnom kontaktnom adresom.
5. Kreirati Blueprint i pričekati da servis postane Live. Bazu i njene vjerodajnice povezuje Blueprint, a Flyway primjenjuje migracije na prvom pokretanju.
6. Otvoriti HTTPS `onrender.com` adresu servisa i prijaviti se postavljenim administratorskim računom. `RENDER_EXTERNAL_URL` automatski daje javnu adresu aplikacije.
7. Nakon uspješne prve prijave ukloniti `ADMIN_PASSWORD` iz okruženja servisa. Postojeći račun ostaje u bazi.
8. Na mobitelu otvoriti istu HTTPS adresu, instalirati aplikaciju i kroz zvonce uključiti push. Dopuštenje i pretplata s lokalne IP adrese ne prenose se na novu domenu.

Automatski deploy je isključen. Nakon kasnijeg pusha koristiti Manual Deploy → Deploy latest commit.

## Ograničenja probe

- Ovo je nova, prazna baza; lokalni članovi, raspored i podaci ne prenose se automatski.
- Besplatna baza istječe nakon 30 dana; služi probi, ne trajnoj evidenciji.
- Servis se uspava nakon 15 minuta bez dolaznog prometa. Podsjetnici i outbox worker ne rade dok spava; obrada se nastavlja kada ga posjet probudi. Prvo otvaranje može potrajati.
- `MAIL_PROVIDER=log` ne šalje e-mail. Pozivnice i reset lozinke zato se ne mogu dovršiti e-mailom; poveznice se namjerno ne ispisuju u log. Administrator se stvara preko varijabli iznad. Za provjeru članova potreban je naknadno HTTP e-mail provider ili plaćeni servis sa SMTP-om.
- Sesije se čuvaju u memoriji, pa restart/deploy traži novu prijavu.
- Push zahtijeva dopuštenje preglednika. Za test samostalne prijave na trening administratoru dodijeliti i ulogu MEMBER.

Dokumentacija: https://render.com/docs/free i https://render.com/docs/blueprint-spec
