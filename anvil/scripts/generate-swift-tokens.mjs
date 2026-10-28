#!/usr/bin/env node
/**
 * Generates Swift design tokens from Anvil's CSS theme files.
 *
 * Source of truth: src/theme-palette.css (four tiers: light, dark,
 * light-high-contrast, dark-high-contrast; OKLCH values).
 *
 * Output: swift/Anvil/Sources/Anvil/Generated/AnvilPalette.swift
 *
 * Parsing is identical to the Android/Flutter/RN generators — they must all
 * produce the same token set. Only the emit stage differs.
 *
 * Run from the repo root:  node scripts/generate-swift-tokens.mjs
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const paletteCss = readFileSync(resolve(repoRoot, 'src/theme-palette.css'), 'utf8')

// ---------------------------------------------------------------------------
// oklch -> sRGB (identical to the other generators)
// ---------------------------------------------------------------------------

function oklchToSrgbHex(lPct, c, hDeg) {
  const L = lPct / 100
  const hr = (hDeg * Math.PI) / 180
  const a = c * Math.cos(hr)
  const b = c * Math.sin(hr)

  const l_ = L + 0.3963377774 * a + 0.2158037573 * b
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b
  const s_ = L - 0.0894841775 * a - 1.291485548 * b

  const l = l_ ** 3
  const m = m_ ** 3
  const s = s_ ** 3

  let r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
  let g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
  let bl = -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s

  const toSrgb = (x) => (x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055)
  r = toSrgb(r)
  g = toSrgb(g)
  bl = toSrgb(bl)

  const to255 = (x) => Math.round(Math.min(1, Math.max(0, x)) * 255)
  return `0x${[r, g, bl]
    .map((v) => to255(v).toString(16).padStart(2, '0').toUpperCase())
    .join('')}`
}

function cssColorToHex(value) {
  const oklch = value.match(/oklch\(\s*([\d.]+)%\s+([\d.]+)\s+([\d.-]+)\s*\)/)
  if (oklch) return oklchToSrgbHex(+oklch[1], +oklch[2], +oklch[3])
  const m = value.trim().match(/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/)
  if (m) {
    let h = m[1]
    if (h.length === 3) h = [...h].map((c) => c + c).join('')
    return `0x${h.toUpperCase()}`
  }
  return null
}

// ---------------------------------------------------------------------------
// Parse the four palette tiers (identical to the other generators)
// ---------------------------------------------------------------------------

const tierColors = { light: {}, dark: {}, lightHC: {}, darkHC: {} }
let selectorBuffer = null
let braceDepth = 0
let currentTier = null

function startTierFor(text) {
  const flat = text.replace(/\s+/g, '')
  if (flat.includes('dark-high-contrast')) return 'darkHC'
  if (flat.includes('light-high-contrast')) return 'lightHC'
  if (flat.startsWith(':root,') || flat.includes(':root.light')) return 'light'
  if (flat.startsWith(':root.dark')) return 'dark'
  return null
}

for (const line of paletteCss.split('\n')) {
  const trimmed = line.trim()
  if (selectorBuffer !== null) {
    selectorBuffer += trimmed
    if (trimmed.includes('{')) {
      currentTier = startTierFor(selectorBuffer)
      selectorBuffer = null
      braceDepth = (trimmed.match(/\{/g) || []).length - (trimmed.match(/\}/g) || []).length
      continue
    }
    continue
  }
  if (trimmed.startsWith(':root')) {
    currentTier = null
    if (trimmed.includes('{')) {
      currentTier = startTierFor(trimmed)
      braceDepth = 1
    } else {
      selectorBuffer = trimmed
    }
    continue
  }
  if (!currentTier) continue

  const opens = (trimmed.match(/\{/g) || []).length
  const closes = (trimmed.match(/\}/g) || []).length
  braceDepth += opens - closes
  if (braceDepth <= 0) {
    currentTier = null
    continue
  }
  if (opens > 0) continue
  if (braceDepth !== 1) continue

  const decl = trimmed.match(/^--color-([a-z0-9-]+):\s*(.+?);$/)
  if (!decl) continue
  const [, name, rawValue] = decl
  const varRef = rawValue.match(/^var\(--color-([a-z0-9-]+)\)$/)
  if (varRef) {
    tierColors[currentTier][name] = { alias: varRef[1] }
    continue
  }
  const hex = cssColorToHex(rawValue)
  if (hex) tierColors[currentTier][name] = { hex }
  else console.warn(`Skipping unrecognized value for --color-${name} (${currentTier}): ${rawValue}`)
}

const EXPECTED_TOKENS_PER_TIER = 311
for (const [tier, colors] of Object.entries(tierColors)) {
  const count = Object.keys(colors).length
  if (count !== EXPECTED_TOKENS_PER_TIER && !tier.endsWith('HC')) {
    throw new Error(
      `Tier ${tier} parsed ${count} tokens, expected ${EXPECTED_TOKENS_PER_TIER}. ` +
        `The CSS shape changed — update the generator.`,
    )
  }
}

tierColors.lightHC = { ...tierColors.light, ...tierColors.lightHC }
tierColors.darkHC = { ...tierColors.dark, ...tierColors.darkHC }

for (const [tier, colors] of Object.entries(tierColors)) {
  const count = Object.keys(colors).length
  if (count !== EXPECTED_TOKENS_PER_TIER) {
    throw new Error(
      `Tier ${tier} has ${count} tokens after HC merging, expected ${EXPECTED_TOKENS_PER_TIER}.`,
    )
  }
}

const STEPS = ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950']
for (const colors of Object.values(tierColors)) {
  for (const step of STEPS) {
    const citrus = colors[`citrus-${step}`]
    if (!citrus) throw new Error(`citrus-${step} missing — primary alias cannot be built`)
    colors[`primary-${step}`] ??= { hex: citrus.hex }
  }
}

for (const colors of Object.values(tierColors)) {
  for (const [name, entry] of Object.entries(colors)) {
    if (entry.alias) {
      const target = colors[entry.alias]
      if (!target || !target.hex)
        throw new Error(`Alias --color-${name} -> --color-${entry.alias} cannot be resolved`)
      entry.hex = target.hex
    }
  }
}

const swiftName = (cssName) => cssName.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase())

// ---------------------------------------------------------------------------
// Emit Swift
// ---------------------------------------------------------------------------

const HEADER = `// GENERATED FILE — DO NOT EDIT.
// Generated by scripts/generate-swift-tokens.mjs from src/theme-palette.css.
// Anvil (web) remains the source of truth for these values.
import SwiftUI

/// Which Anvil palette tier is active.
public enum AnvilThemeTier: Sendable {
    case light
    case dark
    case lightHighContrast
    case darkHighContrast
}
`

function emitTier(enumName, colors, tierLabel) {
  const entries = Object.entries(colors).sort(([a], [b]) => a.localeCompare(b))
  let out = `/// Anvil *${tierLabel}* primitive palette (OKLCH values from theme-palette.css,
/// converted to opaque sRGB).
public enum ${enumName} {
`
  for (const [name, { hex }] of entries) {
    out += `    public static let ${swiftName(name)} = Color(rgb: ${hex})\n`
  }
  out += '}'
  return out
}

const body = [
  emitTier('AnvilLightPalette', tierColors.light, 'light'),
  emitTier('AnvilDarkPalette', tierColors.dark, 'dark'),
  emitTier('AnvilLightHighContrastPalette', tierColors.lightHC, 'light-high-contrast'),
  emitTier('AnvilDarkHighContrastPalette', tierColors.darkHC, 'dark-high-contrast'),
].join('\n\n')

const outPath = resolve(
  repoRoot,
  'swift/Anvil/Sources/Anvil/Generated/AnvilPalette.swift',
)
mkdirSync(dirname(outPath), { recursive: true })
writeFileSync(outPath, HEADER + '\n' + body + '\n')

const counts = Object.entries(tierColors)
  .map(([k, v]) => `${k}: ${Object.keys(v).length}`)
  .join(', ')
console.log(`Wrote ${outPath}`)
console.log(`Colors per tier: ${counts}`)
