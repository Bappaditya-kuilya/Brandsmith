export const AA_TEXT = 4.5

function parseHex(hex: string): [number, number, number] {
  const s = hex.startsWith('#') ? hex.slice(1) : hex
  const full =
    s.length === 3
      ? s
          .split('')
          .map((c) => c + c)
          .join('')
      : s
  if (!/^[0-9a-fA-F]{6}$/.test(full)) return [0, 0, 0]
  const v = parseInt(full, 16)
  return [(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff]
}

function channel(value: number): number {
  const c = value / 255
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

function luminance(hex: string): number {
  const [r, g, b] = parseHex(hex)
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/** WCAG contrast ratio in [1, 21]; argument order does not matter. */
export function contrastRatio(a: string, b: string): number {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

export function meetsAa(fg: string, bg: string): boolean {
  return contrastRatio(fg, bg) >= AA_TEXT
}
