/**
 * Modul <b>attendance</b> - Poslovna jezgra: evidencija PRESENT/ABSENT, masovno označavanje, optimistično zaključavanje, audit. AttendanceService je jedini autoritet za upis prisutnosti.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): training_attendances, attendance_audit_logs</li>
 *   <li>API: /api/v1/trainings/{id}/roster, /api/v1/trainings/{id}/attendance/**</li>
 *   <li>Frontend: features/attendance</li>
 *   <li>Smije ovisiti o: shared, members, scheduling, registrations</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.attendance;
