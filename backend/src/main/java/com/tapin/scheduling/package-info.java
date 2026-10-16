/**
 * Modul <b>scheduling</b> - Ponavljajuća pravila rasporeda, materijalizacija termina, izmjene/otkazivanja termina, treneri termina.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): recurring_schedules, training_sessions, session_coaches, training_session_events</li>
 *   <li>API: /api/v1/trainings (CRUD, cancel), /api/v1/recurring-schedules/**</li>
 *   <li>Frontend: features/trainings</li>
 *   <li>Smije ovisiti o: shared, auth (AuthenticatedUser), members (MembershipDirectory), notifications (outbox)</li>
 *   <li>SPI: {@link com.tapin.scheduling.TrainingParticipants} (implementira registrations) za primatelje obavijesti</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.scheduling;
