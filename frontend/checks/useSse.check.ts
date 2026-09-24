import assert from 'node:assert/strict'
import { createSseParser } from '../src/hooks/useSse.ts'

const events: Array<{ event: string; data: string }> = []
const parser = createSseParser((event, data) => events.push({ event, data }))

// split mid-block, CRLF, comment line, multi-event
parser.push('event: stage_start')
parser.push('ed\r\ndata: {"stage":"S2"}\n: keep-alive\n\nevent: progress\nda')
parser.push('ta: {"message":"Judge is scoring position 2 of 3"}\n\n')
parser.flush()

assert.deepEqual(events, [
  { event: 'stage_started', data: '{"stage":"S2"}' },
  { event: 'progress', data: '{"message":"Judge is scoring position 2 of 3"}' },
])

// flush a trailing block with no terminator
const tail: Array<{ event: string; data: string }> = []
const p2 = createSseParser((event, data) => tail.push({ event, data }))
p2.push('event: error\ndata: timeout')
p2.flush()
assert.deepEqual(tail, [{ event: 'error', data: 'timeout' }])

console.log('useSse parser check: ok')
