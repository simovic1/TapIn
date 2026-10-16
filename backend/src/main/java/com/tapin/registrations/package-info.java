/**
 * Modul <b>registrations</b> - Prijava i otkazivanje prijave člana na termin.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): training_registrations, registration_events</li>
 *   <li>API: /api/v1/trainings/{id}/my-registration</li>
 *   <li>Frontend: features/trainings</li>
 *   <li>Smije ovisiti o: shared, auth, members, scheduling, notifications; stanje prisutnosti čita kroz SPI
 *       {@link com.tapin.registrations.AttendanceStatusLookup} (implementira attendance)</li>
 *   <li>Konkurentnost s evidencijom: {@link com.tapin.registrations.ParticipationLock} – attendance ga MORA uzeti prije
 *       svakog upisa prisutnosti</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.registrations;
