/** The text fields of an order that a person reads. Every one except the tracking number can be missing. */
export interface OrderText {
  trackingNumber: string;
  address1?: string | null | undefined;
  addressKo?: string | null | undefined;
}

const clean = (value: string | null | undefined): string => value?.trim() ?? "";

/**
 * What an order is called: its address, the Korean one when there is one, else the sender's, else the
 * tracking number. No detail part and no name: a delivery is found by where it goes.
 */
export function orderTitle(order: OrderText): string {
  return clean(order.addressKo) || clean(order.address1) || order.trackingNumber;
}

/**
 * The sender's address, to show under the Korean one. Empty when there is no Korean address, since then
 * the sender's address is already the title.
 */
export function orderOriginal(order: OrderText): string {
  return clean(order.addressKo) === "" ? "" : clean(order.address1);
}
