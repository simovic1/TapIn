/**
 * Modul <b>members</b> - Klub, članstvo, uloge (MEMBER/TRAINER/ADMIN), status članstva i profil člana.
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): clubs, club_memberships, membership_roles, membership_status_events</li>
 *   <li>API: /api/v1/members/** (osim /belts i /statistics)</li>
 *   <li>Frontend: features/members</li>
 *   <li>Smije ovisiti o: shared, auth</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.members;
