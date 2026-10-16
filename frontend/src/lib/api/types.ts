/**
 * Convenience aliases over the types generated from api/openapi.yaml (`schema.d.ts`).
 * Never edit schema.d.ts by hand: change the contract and run `npm --prefix api run types`.
 */
import type { components, operations, paths } from './schema';

export type Schemas = components['schemas'];
export type { components, operations, paths };

export type Problem = Schemas['Problem'] & {
  fieldErrors?: Record<string, string>;
  currentState?: unknown;
};
export type ErrorCode = Schemas['ErrorCode'];
export type AttendanceStatus = Schemas['AttendanceStatus'];
export type NotificationStatus = Schemas['NotificationStatus'];
export type UserId = Schemas['UserId'];
export type MembershipId = Schemas['MembershipId'];
export type PageMetadata = Schemas['PageMetadata'];
