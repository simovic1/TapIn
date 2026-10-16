/**
 * Modul <b>auth</b> - Identitet i pristup: prijava/odjava (session), pozivnice, reset lozinke, lozinke i tokeni (hash).
 * <ul>
 *   <li>Tablice (vlasništvo, jedini modul koji u njih piše): users, invitations, password_reset_tokens</li>
 *   <li>API: /api/v1/auth/**, /api/v1/me</li>
 *   <li>Frontend: features/auth</li>
 *   <li>Smije ovisiti o: shared</li>
 * </ul>
 * Pravila vlasništva i imenovanja: {@code docs/architecture/modules.md}.
 */
package com.tapin.auth;
