import { csrfToken } from "./auth.ts";
import { sendWithRefresh } from "./http.ts";

export type SaveOutcome =
  | { kind: "ok"; placed: boolean; reason: string | null }
  | { kind: "invalid" }
  | { kind: "forbidden" }
  | { kind: "missing" };

/** An administrator types the Korean address of an address. The server keeps it as MANUAL and looks for the place. */
export async function saveAddressKo(addressKey: string, addressKo: string): Promise<SaveOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh(`/api/address-ko/${encodeURIComponent(addressKey)}`, {
    method: "PUT",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token === null ? {} : { "X-CSRF-Token": token }),
    },
    body: JSON.stringify({ addressKo }),
  });
  if (response.status === 200) {
    const body: unknown = await response.json();
    const placed = typeof body === "object" && body !== null && "placed" in body && body.placed === true;
    const reason = typeof body === "object" && body !== null && "reason" in body && typeof body.reason === "string" ? body.reason : null;
    return { kind: "ok", placed, reason };
  }
  if (response.status === 400) return { kind: "invalid" };
  if (response.status === 403) return { kind: "forbidden" };
  if (response.status === 404) return { kind: "missing" };
  throw new Error(`Unexpected status ${response.status}`);
}
