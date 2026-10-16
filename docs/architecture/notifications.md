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
| `sent_at`, `provider_message_id` | potvrda providera |

Retry: eksponencijalni backoff 1 min → 2 → 4 → … do 1 h, ±10 % jitter, najviše 8 pokušaja (~3 h), zatim `FAILED`.
Trajne greške (odbijena adresa, neispravna poruka, nedostaje podatak za predložak) idu odmah u `FAILED`.
Postavke: `tapin.outbox.*` u `application.yml`.

Korisni upiti:

```sql
-- red i greške
SELECT status, count(*) FROM email_outbox GROUP BY status;
SELECT id, event_type, recipient_email, attempt_count, next_attempt_at, last_error
FROM email_outbox WHERE status <> 'SENT' ORDER BY created_at;
```

Ponovno slanje `FAILED` poruke nakon otklanjanja uzroka: `NotificationOutbox.retryFailed(id)` (vraća u `PENDING`
s novim brojem pokušaja). Poruke s tajnim sadržajem (pozivnica, reset lozinke) nakon `SENT`/`FAILED` imaju obrisan
payload i ne mogu se ponovno poslati – poslovna radnja se ponavlja (npr. "Ponovno pošalji pozivnicu").

Metrike (Micrometer): `tapin.outbox.messages{status}` i `tapin.outbox.oldest.pending.age` – alarm na rast `FAILED`
ili staru `PENDING` poruku.

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

## Provideri

| `tapin.mail.provider` | Namjena |
|---|---|
| `smtp` (zadano) | Bilo koji SMTP relay ili SMTP endpoint transakcijskog providera. Lokalno: Mailpit iz docker-compose (UI http://localhost:8025). |
| `log` | Zamjenski provider: ne šalje, samo logira primatelja i naslov (tijelo se ne logira jer može sadržavati tajne poveznice). |

Novi provider = implementacija `EmailSender` koja nikad ne baca iznimku za greške dostave, nego vraća
`Accepted`, `TransientFailure(reason, ambiguous)` ili `PermanentFailure(reason)`.

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
