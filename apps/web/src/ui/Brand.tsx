import type { JSX } from "preact";

/** The page title with the UPS shield beside it. The shield is decoration: the title text names the page. */
export function BrandTitle(props: { id: string; children: string }): JSX.Element {
  return (
    <div class="brand-title">
      <img class="brand-logo" src="/ups-logo.png" alt="" width="42" height="50" />
      <h1 id={props.id} class="login-title">
        {props.children}
      </h1>
    </div>
  );
}
