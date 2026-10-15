import type { JSX } from "preact";

/**
 * A labelled text box. While [error] is set the box has a red edge and the message is red; otherwise the
 * gray [hint] shows. The caller decides when an error may show (usually after the person has left the box).
 */
export function TextField(props: {
  id: string;
  label: string;
  value: string;
  onInput: (value: string) => void;
  onBlur: () => void;
  type?: string;
  autocomplete?: string;
  hint?: string | undefined;
  error?: string | undefined;
  large?: boolean;
}): JSX.Element {
  const note = props.error ?? props.hint;
  const noteId = `${props.id}-note`;
  return (
    <div class="field">
      <label htmlFor={props.id}>{props.label}</label>
      <input
        id={props.id}
        class={props.large === true ? "text-input text-input-large" : "text-input"}
        type={props.type ?? "text"}
        autocomplete={props.autocomplete ?? "off"}
        autocapitalize="none"
        spellcheck={false}
        value={props.value}
        aria-invalid={props.error !== undefined}
        aria-describedby={note === undefined ? undefined : noteId}
        onInput={event => props.onInput(event.currentTarget.value)}
        onBlur={props.onBlur}
      />
      {note !== undefined && (
        <span id={noteId} class={props.error === undefined ? "field-hint" : "field-error"}>
          {note}
        </span>
      )}
    </div>
  );
}
