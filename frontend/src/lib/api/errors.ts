import { ApiError } from './client';
import type { ErrorCode } from './types';

/** User-facing Croatian messages for error codes where the server `detail` is not enough or not available. */
const MESSAGES: Partial<Record<ErrorCode, string>> = {
  UNAUTHENTICATED: 'Prijava je istekla. Prijavite se ponovno.',
  INVALID_CREDENTIALS: 'Neispravan e-mail ili lozinka.',
  FORBIDDEN: 'Nemate ovlast za ovu radnju.',
  CSRF_TOKEN_INVALID: 'Sigurnosni token je istekao. Osvježite stranicu i pokušajte ponovno.',
  NOT_FOUND: 'Traženi podatak ne postoji.',
  VERSION_CONFLICT: 'Podatak je u međuvremenu izmijenjen. Osvježite prikaz i pokušajte ponovno.',
  RATE_LIMITED: 'Previše pokušaja. Pričekajte nekoliko minuta i pokušajte ponovno.',
  INTERNAL_ERROR: 'Došlo je do neočekivane pogreške na poslužitelju.',
};

const NETWORK_MESSAGE = 'Poslužitelj nije dostupan. Provjerite vezu i pokušajte ponovno.';

/** Best message for any error thrown by `apiFetch` or the network layer. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const code = error.problem.errorCode;
    return (code && MESSAGES[code]) ?? error.problem.detail ?? `Pogreška ${error.status}.`;
  }
  if (error instanceof TypeError) return NETWORK_MESSAGE; // fetch() network failure
  return 'Došlo je do neočekivane pogreške.';
}

/** Field-level validation messages from a 400 `VALIDATION_FAILED` problem, keyed by field name. */
export function fieldErrors(error: unknown): Record<string, string> {
  if (error instanceof ApiError && error.problem.fieldErrors) return error.problem.fieldErrors;
  return {};
}
