import { csrfToken } from "./auth.ts";
import { sendWithRefresh } from "./http.ts";
import type { components } from "./schema";

type Body = components["schemas"]["Order"];
export type EditableField = NonNullable<Body["edited"]>[number];
export const EDITABLE_FIELDS: readonly EditableField[] = ["customerName", "companyName", "address1", "address2", "postalCode"];
export interface OrderEdit {
  orderId: string;
  field: EditableField;
  /** Null gives the cell back to Ours24. */
  value: string | null;
}

export type EditsOutcome = { kind: "ok" } | { kind: "forbidden" } | { kind: "missing" } | { kind: "invalid" };

/** Saves the cells changed in the order table of [day]. Ours24 is not written; the API shows them over its rows. */
export async function saveOrderEdits(day: string, edits: readonly OrderEdit[]): Promise<EditsOutcome> {
  const token = csrfToken();
  const response = await sendWithRefresh("/api/orders/edits", {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...(token === null ? {} : { "X-CSRF-Token": token }) },
    body: JSON.stringify({ day, edits }),
  });
  if (response.status === 204) return { kind: "ok" };
  if (response.status === 403) return { kind: "forbidden" };
  if (response.status === 404) return { kind: "missing" };
  if (response.status === 400) return { kind: "invalid" };
  throw new Error(`Unexpected status ${response.status}`);
}
