# E-mail obavijesti: transactional outbox i worker

Modul `notifications` (spec §8.3, AC-15). Tablica `email_outbox` (migracija `V004`).

## Tok

```text
business transaction                         OutboxWorker (svakih 10 s, u istoj aplikaciji)
┌───────────────────────────────┐           ┌───────────────────────────────────────────────┐
│ promjena (npr. prisutnost)    │           │ 1. claim: UPDATE ... FOR UPDATE SKIP LOCKED   │
│ NotificationOutbox.enqueue()  │──commit──▶│    (lease locked_by/locked_until, attempt+1)  │
│   INSERT ... ON CONFLICT      │           │ 2. render + send preko EmailSender (bez tx)   │
│   (deduplication_key) NOTHING │           │ 3. zapis ishoda samo ako lease još vrijedi:   │
└───────────────────────────────┘           │    SENT / PENDING + backoff / FAILED          │
                                            └───────────────────────────────────────────────┘
```

- **Rollback poslovne promjene poništava i događaj:** `enqueue` ima `Propagation.MANDATORY` – mora se pozvati
  unutar poslovne transakcije i dijeli njezinu sudbinu. Poziv izvan transakcije baca iznimku.
- **Kvar providera ne poništava promjenu:** slanje se događa tek nakon commita, u workeru; greška samo mijenja
  stanje outbox retka. E-mail nikad ne blokira spremanje evidencije (§14).
- **Deduplikacija:** `deduplication_key` je ključ *poslovnog događaja* (ne pokušaja), npr.
  `ATTENDANCE_RECORDED:audit-981`, `TRAINING_REMINDER:session-12:membership-7`, `INVITATION:invitation-55`.
  Isti događaj ponovno → `EnqueueResult.DUPLICATE` (`notificationStatus=NOT_REQUIRED`), nema drugog retka.
  Ključ mora biti deterministički izveden iz trajnog podatka (ID audit zapisa, prijave, pozivnice), nikad iz
  vremena zahtjeva.

## Stanja i dijagnostika

| Stupac | Značenje |
|---|---|
| `status` | `PENDING` (čeka slanje ili ponovni pokušaj), `SENT` (provider prihvatio), `FAILED` (trajna greška ili iscrpljeni pokušaji) |
| `attempt_count` | broj preuzimanja; povećava se pri preuzimanju (pad workera usred slanja troši pokušaj) |
| `next_attempt_at` | kad redak smije ponovno biti preuzet |
| `last_attempt_at`, `last_error` | zadnji pokušaj i razlog greške (`[smtp, ambiguous] ...`) |
| `locked_by`, `locked_until` | lease workera koji ga trenutno obrađuje |
| `sent_at`, `provider_message_id` | provider je **prihvatio** poruku (nije potvrda dostave u sandučić) |

Retry: eksponencijalni backoff 1 min → 2 → 4 → … do 1 h, ±10 % jitter, najviše 8 pokušaja (~3 h), zatim `FAILED`.
Trajne greške (odbijena adresa, neispravna poruka, nedostaje podatak za predložak) idu odmah u `FAILED`.
Postavke: `tapin.outbox.*` u `application.yml`.

Operativni nadzor (dohvat, upozorenje, retry) opisan je u odjeljku [Operativni nadzor](#operativni-nadzor).

## Konkurentnost

Više workera (više instanci aplikacije) može raditi istodobno:

- preuzimanje je jedna SQL naredba s `FOR UPDATE SKIP LOCKED`, pa dva workera nikad ne preuzmu isti redak;
- preuzeti redak ima lease (`tapin.outbox.lease`, zadano 2 min) – drugi ga worker ne dira dok lease traje;
- ishod se upisuje uvjetno (`WHERE locked_by = :worker`), pa worker kojem je lease istekao ne može prepisati tuđi
  rezultat. SMTP timeouti (10–15 s) su namjerno znatno kraći od leasea.

## Garancija dostave: at-least-once, ne exactly-once

Sustav jamči da se svaki zapisan događaj pokuša poslati dok ne uspije ili ne prijeđe u `FAILED`. **Ne jamči da će
e-mail stići točno jednom.** Dvostruka dostava je moguća kad:

1. provider prihvati poruku, ali odgovor ne stigne do nas (timeout nakon slanja podataka, prekid veze) – greška se
   bilježi kao *ambiguous* i poruka se ponovno šalje;
2. slanje traje dulje od leasea (ili worker padne nakon slanja, a prije upisa `SENT`) pa drugi worker ponovno
   preuzme redak.

Ublažavanje:

- **Stabilan `Message-ID`** (`<tapin-outbox-{id}@tapin.local>`) za sve pokušaje istog retka – klijenti i provideri
  koji deduplikiraju po Message-ID-ju spajaju duplikate.
- **Provider idempotency:** adapter dobiva `idempotencyKey` (= `deduplication_key`). SMTP nema idempotentnost
  zahtjeva, pa ga šaljemo kao zaglavlje `X-TapIn-Idempotency-Key` (informativno). Adapter za HTTP API providera
  koji podržava idempotentnost (npr. `Idempotency-Key` zaglavlje) **mora** proslijediti taj ključ – tada provider
  sam odbacuje ponovljeni zahtjev unutar svog prozora idempotentnosti.
- Poruke su sadržajno idempotentne (potvrda istog stanja), pa je duplikat neugodan, ali ne i štetan.

## Operativni nadzor

BJJ-30, spec §8.3, §13.1, §14. Nema zasebnog admin ekrana: operater koristi API, logove i metrike.

### Stavljeno u red ≠ poslano ≠ dostavljeno

| Gdje | Vrijednost | Značenje |
|---|---|---|
| Poslovni API (`notificationStatus`) | `QUEUED` | obavijest je upisana u outbox u istoj transakciji kao promjena – **ništa više** |
| Poslovni API | `NOT_REQUIRED` | nema nove poruke (ponovljena identična radnja ili nema primatelja) |
| Outbox (`status`) | `PENDING` | čeka slanje ili ponovni pokušaj |
| Outbox | `SENT` + `acceptedByProviderAt` | **provider je prihvatio** poruku; potvrdu dostave nemamo (nema webhooka), pa se nigdje ne tvrdi „dostavljeno” |
| Outbox | `FAILED` | trajna greška, iscrpljeni pokušaji, istekla poruka (`Stale`) ili povučeni podsjetnik |

UI zato govori „stavljena u red za slanje”, nikad „poslana” ili „dostavljena”.

### Pronalaženje neuspjelih poruka

`GET /api/v1/admin/outbox?status=FAILED` (samo ADMIN; prijava kao administrator, isti session cookie kao web):

```bash
curl -s -b cookies.txt "https://<domena>/api/v1/admin/outbox?status=FAILED&size=50"
```

Odgovor sadrži brojače po stanju, starost najstarije `PENDING` poruke, broj `FAILED` u zadnja 24 h, stanje upozorenja
i po poruci: tip, primatelja, `trainingId`, deduplikacijski ključ, `attemptCount`/`maxAttempts`, vrijeme zadnjeg i
sljedećeg pokušaja, `lastError`, `acceptedByProviderAt` i `retryable`. **Sadržaj poruke (payload) se nikad ne vraća**
– pozivnice i reset lozinke sadrže tajne poveznice. `last_error` se pri upisu i čitanju čisti (`OutboxErrors`):
parovi `password=`, `token:`, `Authorization: Bearer …`, putanje/fragmenti poveznica i dugi neprozirni nizovi postaju `***`.

Isto izravno u bazi (bez `payload` stupca!):

```sql
SELECT status, count(*) FROM email_outbox GROUP BY status;
SELECT id, event_type, recipient_email, attempt_count, last_attempt_at, next_attempt_at, last_error
FROM email_outbox WHERE status <> 'SENT' ORDER BY created_at;
```

### Upozorenje na dugotrajne greške

`OutboxMonitor` svake minute (`tapin.outbox.alert.interval`) provjerava ima li `PENDING` poruka starijih od
`tapin.outbox.alert.after` (zadano 30 min ≈ pet neuspjelih pokušaja). Ako ima, red je **degradiran**:

- log `ERROR … OUTBOX_DEGRADED: N e-mail(s) PENDING for more than PT30M …, last error: …` pri ulasku i zatim svakih
  `tapin.outbox.alert.repeat` (1 h); izlazak bilježi `WARN … OUTBOX_RECOVERED`. Alarm u log sustavu: tekst
  `OUTBOX_DEGRADED`.
- metrika `tapin.outbox.degraded` = 1; u API-ju `alert.degraded`, `degradedSince`, `stuckPending`, `latestError`.
- uzrok „last error: none” znači da poruke nitko ne obrađuje – provjeriti radi li worker (`tapin.outbox.worker.enabled`,
  log `Outbox worker … started`, `Outbox poll failed`).

Namjerno **nije** health indikator: `DOWN` bi orkestrator protumačio kao kvar aplikacije i restartao je ili izbacio
iz prometa, a evidencija i dalje radi ispravno (§14 – e-mail nikad ne blokira spremanje).

Metrike (Micrometer, `/actuator/metrics/<ime>` samo za ADMIN):

| Metrika | Opis | Prijedlog alarma |
|---|---|---|
| `tapin.outbox.messages{status}` | broj redaka po stanju | rast `FAILED` |
| `tapin.outbox.oldest.pending.age` | starost najstarije `PENDING` poruke (s) | > 1800 |
| `tapin.outbox.degraded` | 1 dok traje degradacija | = 1 |
| `tapin.outbox.deliveries{outcome}` | pokušaji po ishodu: `SENT` (prihvaćeno), `RETRY`, `FAILED`, `LEASE_LOST` | udio `RETRY` |

### Siguran ručni retry

Najprije otkloniti uzrok (vjerodajnice, DNS, kvota providera, pogrešna adresa člana). Zatim:

```bash
curl -s -b cookies.txt -H "X-XSRF-TOKEN: <token>" -X POST "https://<domena>/api/v1/admin/outbox/<id>/retry"
```

- Dopušteno samo za `FAILED` poruku s `retryable=true`: vraća je u `PENDING` s novim brojem pokušaja; isti redak,
  isti deduplikacijski ključ i isti `Message-ID`, pa ne nastaje druga poruka.
- Drugi poziv (dvoklik, ponovljeni curl) vraća `422` jer poruka više nije `FAILED` – retry je siguran za ponavljanje.
- `422` i za poruku s tajnom poveznicom (payload je obrisan): ponoviti poslovnu radnju („Ponovno pošalji pozivnicu”,
  novi zahtjev za reset lozinke) – to izdaje novi token.
- `422` i za isteklu poruku (`expires_at`, npr. podsjetnik nakon početka treninga, D-04): ne šalje se naknadno.
- `PENDING` poruke ne treba dirati: nakon oporavka providera same odlaze po rasporedu ponovnih pokušaja (razmak
  najviše `max-backoff` = 1 h ± 10 %). Masovni retry nakon duljeg ispada (više od ~3 h, pokušaji iscrpljeni): pozvati endpoint
  za svaki `retryable` redak iz popisa – poruke su sadržajno idempotentne, a at-least-once vrijedi kao i inače.
- **Ne** mijenjati `email_outbox` ručnim `UPDATE`-om: zaobilazi provjere iznad (tajni payload, istek, lease).

### Oporavak nakon kvara providera (provjereno)

`OutboxOperationsIntegrationTest` simulira ispad providera: evidencija dolaska i ispravak spremaju se normalno
(`notificationStatus=QUEUED`), poruke ostaju `PENDING` s rastućim `attemptCount` i očišćenim `lastError`, nakon
30 min upozorenje se uključuje, a kad provider ponovno radi, red se sam isprazni (`SENT`), upozorenje se gasi i
nijedna poruka nije poslana dvaput. Test pokriva i ručni retry (dvoklik, tajni payload, 404) i pristup samo za ADMIN.

## Provideri

| `tapin.mail.provider` | Namjena |
|---|---|
| `smtp` (zadano) | Bilo koji SMTP relay ili SMTP endpoint transakcijskog providera. Lokalno: Mailpit iz docker-compose (UI http://localhost:8025). |
| `log` | Zamjenski provider: ne šalje, samo logira primatelja i naslov (tijelo se ne logira jer može sadržavati tajne poveznice). |

Novi provider = implementacija `EmailSender` koja nikad ne baca iznimku za greške dostave, nego vraća
`Accepted`, `TransientFailure(reason, ambiguous)` ili `PermanentFailure(reason)`.

## Katalog poruka (§8.1, BJJ-28)

| Tip | Primatelj | Okidač | Sadržaj |
|---|---|---|---|
| `INVITATION` | pozvani korisnik | poziv / ponovno slanje | poveznica `/pozivnica#token`, rok |
| `PASSWORD_RESET` | korisnik | zahtjev za reset | poveznica `/nova-lozinka#token`, rok |
| `REGISTRATION_CONFIRMED` | član | nova prijava / ponovna prijava | naziv, lokalni datum i vrijeme, „prijava nije potvrda dolaska”, poveznica |
| `REGISTRATION_CANCELLED` | član | sam otkaže prijavu | „Otkazali ste prijavu …” – trening se održava |
| `TRAINING_CANCELLED` | samo `REGISTERED` članovi | trener otkaže termin | „Trening je otkazan …”, razlog |
| `TRAINING_RESCHEDULED` | samo `REGISTERED` članovi | promjena početka termina | staro i novo vrijeme |
| `TRAINING_REMINDER` | `REGISTERED` član s uključenim podsjetnikom | `start_at − 3 h` (BJJ-29) | vrijeme, poveznica za otkaz |
| `ATTENDANCE_RECORDED` | samo pogođeni član | svaka stvarna promjena prisutnosti (pojedinačno, masovno, povijesno, ispravak, poništenje) | novi status |

Promocija pojasa ne šalje poruku. Podatke o terminu (`trainingName`, `trainingTime` u Europe/Zagreb, `trainingUrl`)
daje scheduling (`SessionRef.emailValues()`), pa su sve poruke o istom treningu dosljedne. `{name?}` u predlošku je
neobvezan placeholder; za starije retke bez `trainingName` koristi se „Trening”.
`NotificationCatalogIntegrationTest` provjerava primatelje, sadržaj i poveznice kroz pravi outbox procesor i testni
provider koji ništa ne šalje.

## Podsjetnik 3 sata prije treninga (§8.2, BJJ-29, odluka D-04)

- `registrations.TrainingReminderService` (posao svake minute, `tapin.reminders.*`): za svaki neotkazani trening
  kojem je `start_at − 3 h` prošao, a još nije počeo, upisuje `TRAINING_REMINDER` članovima čija je prijava
  `REGISTERED` i napravljena **najkasnije u trenutku dospijeća** – kasnija (i ponovna) prijava dobiva potvrdu, ali ne
  retroaktivni podsjetnik. Dospijeće je trenutak (`Instant`), pa je za 18:30 Zagreb uvijek 15:30 Zagreb.
- **D-04 – tolerancija:** propušteno dospijeće (restart, ispad) nadoknađuje se do početka treninga. Poruka nosi
  `expires_at = start_at`; ako je do početka treninga provider nije prihvatio, processor je označi `FAILED` („Stale”)
  i više je ne šalje.
- Ključ `TRAINING_REMINDER:session-{id}:membership-{id}` ne sadrži vrijeme: restart, dva workera ni pomicanje
  termina ne stvaraju drugi podsjetnik. Otkazivanje termina (pojedinačno ili kroz pravilo) povlači podsjetnike koji
  još čekaju (`NotificationOutbox.discardPending`).
- Član ga isključuje na „Moj profil” (`users.training_reminders_enabled`, `PATCH /me/profile`); cijela aplikacija
  sa `REMINDERS_ENABLED=false`.

## Za proizvođače događaja (drugi moduli)

```java
@Transactional
public AttendanceResult setAttendance(...) {
    // ... poslovna promjena + audit zapis (auditId)
    EnqueueResult result = notificationOutbox.enqueue(OutboxMessage.of(
            EmailEventType.ATTENDANCE_RECORDED, userId, email,
            Map.of("firstName", firstName, "trainingTime", "utorak 20. 10. 2026. u 18:30", "attendanceStatus", "prisutan"),
            "ATTENDANCE_RECORDED:audit-" + auditId).forSession(sessionId));
    return new AttendanceResult(..., result.notificationStatus());
}
```

Payload sadrži vrijednosti spremne za prikaz (datumi već formatirani za Europe/Zagreb). Za poveznice s tokenom
koristiti `.withSecret()`.
