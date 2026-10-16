/**
 * Modul <b>registrations</b> - Prijava i otkazivanje prijave člana na termin.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): training_registrations, registration_events</li>
 *   <li>API: /api/v1/trainings/{id}/my-registration</li>
 *   <li>Frontend: features/trainings</li>
 *   <li>Smije ovisiti o: shared, members, scheduling, attendance (samo čitanje stanja)</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.registrations;
