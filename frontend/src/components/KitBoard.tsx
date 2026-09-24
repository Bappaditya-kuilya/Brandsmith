import type { ReactNode } from 'react'
import type { BrandDna, LaunchAssets, Palette, VoiceSpec } from '../api/types'

function Field({ label, value }: { label: string; value?: string | null }) {
  if (!value) return null
  return (
    <div>
      <dt className="text-xs font-medium text-ink-3">{label}</dt>
      <dd className="mt-0.5 text-sm text-ink">{value}</dd>
    </div>
  )
}

function VoiceSummary({ voice }: { voice: VoiceSpec }) {
  const [min, max] = voice.sentenceWords
  return (
    <dl className="grid gap-3 sm:grid-cols-2">
      <Field label="Formality" value={`${voice.formality} of 5`} />
      <Field label="Sentence length" value={`${min}–${max} words`} />
      <Field label="Humor" value={voice.humorLevel} />
      <div>
        <dt className="text-xs font-medium text-ink-3">Banned words</dt>
        <dd className="mt-1 flex flex-wrap gap-1">
          {voice.bannedWords.length === 0 ? (
            <span className="text-sm text-ink-3">None</span>
          ) : (
            voice.bannedWords.map((word) => (
              <span
                key={word}
                className="rounded-full border border-red-200 bg-red-50 px-2 py-0.5 text-xs text-red-700"
              >
                {word}
              </span>
            ))
          )}
        </dd>
      </div>
      {voice.signatureMoves.length > 0 && (
        <div className="sm:col-span-2">
          <dt className="text-xs font-medium text-ink-3">
            Signature moves
          </dt>
          <dd>
            <ol className="mt-1 list-decimal space-y-0.5 pl-5 text-sm text-ink">
              {voice.signatureMoves.map((move) => (
                <li key={move}>{move}</li>
              ))}
            </ol>
          </dd>
        </div>
      )}
    </dl>
  )
}

function PaletteSwatches({ palette }: { palette: Palette }) {
  const tokens: Array<[string, string]> = [
    ['Background', palette.bg],
    ['Surface', palette.surface],
    ['Accent', palette.accent],
    ['Foreground', palette.fg],
    ['Muted', palette.muted],
  ]
  return (
    <ul className="grid grid-cols-2 gap-3 sm:grid-cols-5">
      {tokens.map(([label, hex]) => (
        <li key={label} className="overflow-hidden rounded-lg border border-line">
          <div className="h-14 w-full" style={{ backgroundColor: hex }} aria-hidden="true" />
          <div className="px-2 py-1.5">
            <p className="text-xs font-medium text-ink">{label}</p>
            <p className="font-mono text-xs text-ink-3">{hex}</p>
          </div>
        </li>
      ))}
    </ul>
  )
}

function AssetsSection({ assets }: { assets: LaunchAssets }) {
  return (
    <div className="space-y-4" data-stub="kit-assets">
      <div className="rounded-lg border border-line bg-surface p-4">
        <p className="text-xs font-medium text-ink-3">Hero</p>
        <p className="mt-1 text-lg font-semibold text-ink">{assets.hero.headline}</p>
        <p className="mt-0.5 text-sm text-ink-2">{assets.hero.subhead}</p>
        <p className="mt-2 inline-block rounded-full bg-accent px-3 py-1 text-xs font-semibold text-ink">
          {assets.hero.cta}
        </p>
      </div>

      <div className="rounded-lg border border-line bg-surface p-4">
        <p className="text-xs font-medium text-ink-3">Pitch</p>
        <p className="mt-1 text-sm text-ink">{assets.pitch}</p>
      </div>

      <div className="rounded-lg border border-line bg-surface p-4">
        <p className="text-xs font-medium text-ink-3">Social posts</p>
        <ol className="mt-2 list-decimal space-y-2 pl-5 text-sm text-ink">
          {assets.posts.map((post, i) => (
            <li key={i}>{post}</li>
          ))}
        </ol>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-lg border border-line bg-surface p-4">
          <p className="text-xs font-medium text-ink-3">Bio · short</p>
          <p className="mt-1 text-sm text-ink">{assets.bioShort}</p>
        </div>
        <div className="rounded-lg border border-line bg-surface p-4">
          <p className="text-xs font-medium text-ink-3">Bio · long</p>
          <p className="mt-1 text-sm text-ink">{assets.bioLong}</p>
        </div>
      </div>
    </div>
  )
}

export interface KitBoardProps {
  dna: BrandDna
  /** Header rendered above the board (name/tagline live in identity for the kit). */
  heading?: ReactNode
}

/** Read-only brand board: position, name, tagline, voice, palette, assets. Shared by kit + share. */
export function KitBoard({ dna, heading }: KitBoardProps) {
  const position = dna.position
  const identity = dna.identity
  const hasAnything =
    identity?.name ||
    position?.category ||
    dna.voice ||
    dna.visual?.palette ||
    dna.assets

  if (!hasAnything) {
    return (
      <p className="rounded-lg border border-dashed border-line-2 p-6 text-center text-sm text-ink-2">
        Nothing in the kit yet. Finish positioning, identity and launch assets first.
      </p>
    )
  }

  return (
    <div className="space-y-6" data-stub="kit-board">
      {heading}

      <section aria-labelledby="kit-position" className="space-y-3">
        <h2 id="kit-position" className="text-lg font-semibold text-ink">
          Position
        </h2>
        <dl className="grid gap-3 rounded-lg border border-line bg-surface p-4 sm:grid-cols-2">
          <Field label="Category" value={position?.category} />
          <Field label="Target" value={position?.target} />
          <Field label="Differentiator" value={position?.differentiator} />
          <Field label="Value proposition" value={position?.valueProposition} />
        </dl>
      </section>

      <section aria-labelledby="kit-identity" className="space-y-3">
        <h2 id="kit-identity" className="text-lg font-semibold text-ink">
          Identity
        </h2>
        <div className="rounded-lg border border-line bg-surface p-4">
          <p className="text-2xl font-bold tracking-tight text-ink">
            {identity?.name ?? '—'}
          </p>
          {identity?.tagline && (
            <p className="mt-1 text-base italic text-ink-2">{identity.tagline}</p>
          )}
          {identity?.pitch && <p className="mt-3 text-sm text-ink-2">{identity.pitch}</p>}
        </div>
      </section>

      {dna.voice && (
        <section aria-labelledby="kit-voice" className="space-y-3">
          <h2 id="kit-voice" className="text-lg font-semibold text-ink">
            Voice
          </h2>
          <div className="rounded-lg border border-line bg-surface p-4">
            <VoiceSummary voice={dna.voice} />
          </div>
        </section>
      )}

      {dna.visual?.palette && (
        <section aria-labelledby="kit-palette" className="space-y-3">
          <h2 id="kit-palette" className="text-lg font-semibold text-ink">
            Palette
          </h2>
          <PaletteSwatches palette={dna.visual.palette} />
          {dna.visual.fonts.length > 0 && (
            <p className="text-sm text-ink-2">
              <span className="font-medium">Fonts:</span> {dna.visual.fonts.join(' · ')}
            </p>
          )}
        </section>
      )}

      {dna.assets && (
        <section aria-labelledby="kit-assets" className="space-y-3">
          <h2 id="kit-assets" className="text-lg font-semibold text-ink">
            Launch assets
          </h2>
          <AssetsSection assets={dna.assets} />
        </section>
      )}
    </div>
  )
}

export { AssetsSection as LaunchAssetsBoard }
