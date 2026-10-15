/**
 * The noun with its subject particle: 이 after a final consonant (받침), 가 after a vowel.
 * A noun that does not end in Hangul gets 이.
 */
export function subject(noun: string): string {
  const last = [...noun.trim()].at(-1);
  const code = last?.codePointAt(0) ?? 0;
  const isHangul = code >= 0xac00 && code <= 0xd7a3;
  const hasFinal = !isHangul || (code - 0xac00) % 28 !== 0;
  return noun + (hasFinal ? "이" : "가");
}
