import { ApiError } from "./http.ts";
import type { PhoneStatus } from "./phone.ts";

/**
 * The browser's part of phone proof, and nothing else: solve the reCAPTCHA, and turn the code the person typed
 * into a Firebase ID token. The server sends the message and checks the token. The Firebase SDK is loaded the
 * first time it is needed, so the page does not carry it for people who never verify a phone.
 */
export interface FirebaseSettings {
  apiKey: string;
  projectId: string;
}

export function settingsOf(status: PhoneStatus): FirebaseSettings | null {
  const { apiKey, projectId } = status;
  return typeof apiKey === "string" && typeof projectId === "string" ? { apiKey, projectId } : null;
}

/** The SDK's own error code (for example auth/captcha-check-failed), so a failure can be named and fixed. */
function providerError(step: string, error: unknown): ApiError {
  const code =
    typeof error === "object" && error !== null && "code" in error && typeof error.code === "string" ? error.code : "unknown";
  const detail = error instanceof Error ? ` ${error.name}: ${error.message}`.slice(0, 160) : "";
  return new ApiError("provider", `인증 서비스 오류 (${step}: ${code})${detail}`);
}

async function load(settings: FirebaseSettings) {
  const [app, auth] = await Promise.all([import("firebase/app"), import("firebase/auth")]);
  const instance =
    app.getApps()[0] ??
    app.initializeApp({
      apiKey: settings.apiKey,
      projectId: settings.projectId,
      authDomain: `${settings.projectId}.firebaseapp.com`,
    });
  return { auth, instance: auth.getAuth(instance) };
}

/** An invisible reCAPTCHA token for one send. Each send needs a new one. */
export async function solveChallenge(settings: FirebaseSettings, holder: HTMLElement): Promise<string> {
  const slot = document.createElement("div");
  holder.appendChild(slot);
  try {
    const { auth, instance } = await load(settings);
    const verifier = new auth.RecaptchaVerifier(instance, slot, { size: "invisible" });
    try {
      return await verifier.verify();
    } finally {
      verifier.clear();
    }
  } catch (error) {
    throw providerError("reCAPTCHA", error);
  } finally {
    slot.remove();
  }
}

/** The ID token for the number, from the session reference the server returned and the code the person typed. */
export async function exchangeCode(settings: FirebaseSettings, reference: string, code: string): Promise<string> {
  try {
    const { auth, instance } = await load(settings);
    const credential = auth.PhoneAuthProvider.credential(reference, code);
    const result = await auth.signInWithCredential(instance, credential);
    try {
      return await result.user.getIdToken();
    } finally {
      await auth.signOut(instance);
    }
  } catch (error) {
    throw providerError("code", error);
  }
}
