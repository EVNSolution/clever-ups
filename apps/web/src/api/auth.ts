/**
 * The session as the page sees it. The cookies are HttpOnly, so the page never holds a token: it holds only
 * the CSRF token, in memory, and asks the server to renew the cookies when an API call answers 401.
 * This file has no imports on purpose: http.ts uses it, so it must not use http.ts.
 */
let csrf: string | null = null;

/** The token a POST must send in X-CSRF-Token, or null before the first sign-in or /api/me answer. */
export const csrfToken = (): string | null => csrf;

/** Reads the token from a response that carries one (sign-in, refresh, /api/me). */
export function rememberCsrf(headers: Headers): void {
  const token = headers.get("X-CSRF-Token");
  if (token !== null && token !== "") csrf = token;
}

export function forgetCsrf(): void {
  csrf = null;
}

let inFlight: Promise<boolean> | null = null;

/**
 * Trades the refresh cookie for new cookies. Calls made at the same time share one request: the server
 * replaces the refresh token at every use, so a second request would carry a token that is already replaced.
 * Resolves true when new cookies were set, false when the person has to sign in again. It never throws.
 */
export function refreshSession(): Promise<boolean> {
  inFlight ??= attempt().finally(() => {
    inFlight = null;
  });
  return inFlight;
}

async function attempt(): Promise<boolean> {
  try {
    const response = await fetch("/api/auth/refresh", {
      method: "POST",
      headers: { "X-UPS-Refresh": "1" },
      credentials: "same-origin",
    });
    if (response.status !== 204) return false;
    rememberCsrf(response.headers);
    return true;
  } catch {
    return false;
  }
}
