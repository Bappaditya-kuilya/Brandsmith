import assert from 'node:assert/strict'
import { AA_TEXT, contrastRatio, meetsAa } from '../src/lib/wcag.ts'

assert.equal(Math.round(contrastRatio('#000000', '#ffffff') * 100) / 100, 21)
assert.equal(Math.round(contrastRatio('#ffffff', '#000000') * 100) / 100, 21)
assert.ok(Math.abs(contrastRatio('#777777', '#ffffff') - 4.48) < 0.05)
assert.ok(meetsAa('#000000', '#ffffff'))
assert.ok(!meetsAa('#777777', '#ffffff'))
assert.equal(AA_TEXT, 4.5)
// 3-digit shorthand equals 6-digit
assert.equal(contrastRatio('#fff', '#000000'), contrastRatio('#ffffff', '#000000'))

console.log('wcag check: ok')
