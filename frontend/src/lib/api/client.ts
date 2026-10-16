/**
 * Thin fetch wrapper for the TapIn API (`/api/v1`).
 * - Session cookie auth: requests are same-origin, cookies are sent automatically.
 * - CSRF: the backend sets a readable `XSRF-TOKEN` cookie; it is echoed in `X-XSRF-TOKEN` for unsafe methods.
 * - Errors: `application/problem+json` bodies are turned into {@link ApiError}.
 */

export const API_BASE = '/api/v1';

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  errorCode?: string;
  fieldErrors?: Record<string, string>;
}

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.problem = problem;
  }

  get status() {
    return this.problem.status;
  }
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json, application/problem+json');
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  if (!SAFE_METHODS.has(method)) {
    const token = readCookie('XSRF-TOKEN');
    if (token) headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
  }

  const response = await fetch(`${API_BASE}${path}`, { ...init, method, headers, credentials: 'same-origin' });

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as Partial<Problem> | null;
    throw new ApiError({ status: response.status, ...body });
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
