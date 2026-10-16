/**
 * Modul <b>scheduling</b> - Ponavljajuća pravila rasporeda, materijalizacija termina, izmjene/otkazivanja termina, treneri termina.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): recurring_schedules, training_sessions, session_coaches</li>
 *   <li>API: /api/v1/trainings (CRUD, cancel), /api/v1/recurring-schedules/**</li>
 *   <li>Frontend: features/trainings</li>
 *   <li>Smije ovisiti o: shared, members</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.scheduling;
