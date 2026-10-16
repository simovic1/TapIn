/**
 * Modul <b>reporting</b> - Statistika i izvještaji (samo čitanje), CSV izvoz. Ne posjeduje poslovne tablice; koristi read-only upite/poglede.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): nema tablica; repeatable migracije R__reporting_* (pogledi)</li>
 *   <li>API: /api/v1/reports/**, /api/v1/me/statistics, /api/v1/members/{id}/statistics</li>
 *   <li>Frontend: features/statistics</li>
 *   <li>Smije ovisiti o: shared + read-only pristup svim modulima</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.reporting;
