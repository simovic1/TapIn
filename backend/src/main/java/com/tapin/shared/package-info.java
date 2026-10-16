/**
 * Shared kernel: cross-cutting infrastructure only (security, error handling, OpenAPI, clock/time zone,
 * common API types). Contains no business logic and owns no database tables.
 * <p>
 * Every other module may depend on {@code shared}; {@code shared} depends on no business module.
 * See {@code docs/architecture/modules.md}.
 */
package com.tapin.shared;
