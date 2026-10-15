import type { Resource } from "./useResource";

const NOTHING: Resource<never> = {
  items: [],
  loading: false,
  error: null,
  updatedAt: null,
  incomplete: false,
  reload: () => undefined,
};

/**
 * What an account without an active membership sees in every list: an empty one, at once, with no request.
 * The page is the normal page; only the information is hidden.
 */
export const hiddenResource = <T,>(): Resource<T> => NOTHING;
