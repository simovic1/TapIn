/**
 * Modul <b>notifications</b> - Transactional outbox, worker za slanje e-maila, retry/backoff, deduplikacija, podsjetnici, HR predlošci.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): email_outbox</li>
 *   <li>API: nema javnog API-ja</li>
 *   <li>Frontend: -</li>
 *   <li>Smije ovisiti o: shared (ostali moduli upisuju događaje kroz NotificationOutbox API)</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.notifications;
