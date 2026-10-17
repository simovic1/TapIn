import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { ApiError, apiFetch } from '../api/client';
import type { Schemas } from '../api/types';
import type { CurrentUser } from './roles';
import { sessionExpiry } from './sessionExpiry';

export const sessionKeys = {
  currentUser: ['session', 'current-user'] as const,
};

/**
 * `/me` returns 401 when nobody is signed in; that is a normal state, not an error. If a user *was* signed in,
 * the 401 means the session expired.
 */
async function fetchCurrentUser(client: QueryClient): Promise<CurrentUser | null> {
  try {
    return await apiFetch<CurrentUser>('/me');
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      if (client.getQueryData<CurrentUser | null>(sessionKeys.currentUser)) sessionExpiry.markExpired();
      return null;
    }
    throw error;
  }
}

export function useCurrentUser() {
  return useQuery({
    queryKey: sessionKeys.currentUser,
    queryFn: ({ client }) => fetchCurrentUser(client),
    staleTime: 5 * 60_000,
    meta: { ignoreUnauthorized: true },
  });
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (credentials: Schemas['LoginRequest']) =>
      apiFetch<CurrentUser>('/auth/login', { method: 'POST', body: JSON.stringify(credentials) }),
    meta: { ignoreUnauthorized: true },
    onSuccess: (user) => {
      sessionExpiry.clear();
      // Data cached for a previous user must never leak into the new session.
      queryClient.clear();
      queryClient.setQueryData(sessionKeys.currentUser, user);
    },
  });
}

/** Activates an invitation; the server signs the new user in (new session), exactly like a login. */
export function useAcceptInvitation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: Schemas['AcceptInvitationRequest']) =>
      apiFetch<CurrentUser>('/auth/invitations/accept', { method: 'POST', body: JSON.stringify(request) }),
    meta: { ignoreUnauthorized: true },
    onSuccess: (user) => {
      sessionExpiry.clear();
      queryClient.clear();
      queryClient.setQueryData(sessionKeys.currentUser, user);
    },
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      try {
        if ('serviceWorker' in navigator) {
          const registration = await navigator.serviceWorker.getRegistration();
          const subscription = await registration?.pushManager?.getSubscription();
          if (subscription) {
            try {
              await apiFetch('/me/push-subscription', { method: 'DELETE', body: JSON.stringify({ endpoint: subscription.endpoint }) });
            } finally {
              await subscription.unsubscribe();
            }
          }
        }
      } finally {
        await apiFetch<void>('/auth/logout', { method: 'POST' });
      }
    },
    meta: { ignoreUnauthorized: true },
    onSettled: () => {
      queryClient.clear();
      queryClient.setQueryData(sessionKeys.currentUser, null);
    },
  });
}

/**
 * Called for every failed query/mutation. A 401 while a user is signed in means the server-side session is gone
 * (timeout, logout elsewhere, server restart): drop cached data and mark the session expired. Route guards then
 * redirect to the login page, which explains what happened and returns the user to where they were.
 */
export function handleUnauthorized(queryClient: QueryClient, error: unknown) {
  if (!(error instanceof ApiError) || error.status !== 401) return;
  if (error.problem.errorCode === 'INVALID_CREDENTIALS') return;

  const user = queryClient.getQueryData<CurrentUser | null>(sessionKeys.currentUser);
  if (!user) return;

  sessionExpiry.markExpired();
  queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
  queryClient.setQueryData(sessionKeys.currentUser, null);
}
