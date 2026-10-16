/**
 * Modul <b>belts</b> - Povijest pojaseva (bez crtica), promocije, ispravci uz audit, efektivna razdoblja.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): member_belt_periods, belt_period_audit_logs</li>
 *   <li>API: /api/v1/members/{id}/belts/**, /api/v1/me/belts</li>
 *   <li>Frontend: features/belts</li>
 *   <li>Smije ovisiti o: shared, members</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.belts;
