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
- `MAIL_PROVIDER=log` ne šalje e-mail. Pozivnice i reset lozinke zato se ne mogu dovršiti e-mailom; poveznice se namjerno ne ispisuju u log. Administrator se stvara preko varijabli iznad. Za stvarno slanje uključiti Gmail HTTPS adapter prema uputama ispod.
- Sesije se čuvaju u memoriji, pa restart/deploy traži novu prijavu.
- Push zahtijeva dopuštenje preglednika. Za test samostalne prijave na trening administratoru dodijeliti i ulogu MEMBER.

Dokumentacija: https://render.com/docs/free i https://render.com/docs/blueprint-spec

## Gmail bez vlastite domene (Free Render)

Adapter `MAIL_PROVIDER=gmail` šalje kroz HTTPS Gmail API, bez SMTP portova. Koristi samo OAuth scope `https://www.googleapis.com/auth/gmail.send`; ne čita sandučić. Račun pošiljatelja autorizira vlasnik računa, ne članovi kluba. Push ostaje nepromijenjen.

1. U Google Cloud Console napraviti projekt TapIn i uključiti **Gmail API** kroz APIs & Services → Library.
2. U Google Auth Platform postaviti Branding (TapIn, kontaktni email), Audience **External**, te kao testnog korisnika dodati `bd.samobor@gmail.com`. U Data Access dodati samo scope `https://www.googleapis.com/auth/gmail.send`.
3. Napraviti OAuth client tipa **Web application**. Za ovaj jednokratni postupak dodati Authorized redirect URI `https://developers.google.com/oauthplayground`.
4. Otvoriti https://developers.google.com/oauthplayground → postavke (zupčanik) → **Use your own OAuth credentials**, unijeti vlastiti Client ID i Client secret. Access type mora biti **Offline**. Ne koristiti Playgroundove zadane vjerodajnice jer se njegovi refresh tokeni automatski opozivaju.
5. U Step 1 upisati scope `https://www.googleapis.com/auth/gmail.send`, odabrati Authorize APIs i odobriti slanje kao `bd.samobor@gmail.com`. U Step 2 odabrati **Exchange authorization code for tokens**. Refresh token je tajna; ne dijeliti screenshot niti ga slati u chat.
6. U Render → tapin-trial → Environment unijeti:
   - `MAIL_PROVIDER=gmail`
   - `MAIL_FROM=bd.samobor@gmail.com` (mora odgovarati autoriziranom Gmail računu)
   - `GMAIL_CLIENT_ID` = vlastiti OAuth Client ID
   - `GMAIL_CLIENT_SECRET` = vlastiti OAuth Client secret
   - `GMAIL_REFRESH_TOKEN` = dobiveni refresh token
   - `MAIL_HOST=localhost` može ostati: Gmail adapter ne koristi SMTP.
7. Deployati commit s Gmail adapterom i spremiti varijable uz redeploy. Postojeći `render.yaml` namjerno zadržava siguran probni `log` način dok se ne poveže Google račun; pri ponovnom Blueprint syncu provjeriti da se `MAIL_PROVIDER` i `MAIL_FROM` ne vrate na probne vrijednosti.
8. Ponovno poslati pozivnicu kroz administraciju. Ranije poruke obrađene kroz `log` provider ne šalju se automatski ponovno. Provjeriti primitak i otvaranje poveznice na Render adresi.

**Trajanje autorizacije:** dok je Google OAuth aplikacija External / Testing, refresh token s Gmail dozvolom istječe nakon 7 dana. Za probu se može ponovno autorizirati. Za trajni rad postaviti odgovarajući Production status i ponovno autorizirati; Google može tražiti provjeru aplikacije ovisno o načinu uporabe. Production status nije isto što i Googleova verifikacija. Tokeni se mogu opozvati i kasnije, pa pratiti greške autorizacije u outboxu. Gmail ograničenja slanja i dalje vrijede.

Adapter namjerno ne zapisuje OAuth tokene, tijelo Googleova odgovora ni sadržaj maila u log. Autorizacijske i quota greške ponavljaju se prema postojećem outbox backoffu; neispravne poruke završavaju kao trajna greška. Gmail nema idempotentno slanje: nakon nejasnog mrežnog ishoda moguća je duplikat poruka.

Izvori:
- https://developers.google.com/workspace/gmail/api/guides/sending
- https://developers.google.com/identity/protocols/oauth2/web-server#offline
- https://developers.google.com/identity/protocols/oauth2#expiration
- https://developers.google.com/oauthplayground
