import assert from 'node:assert/strict'
import { createSseParser, createSseRunner } from '../src/hooks/useSse.ts'

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

function sseResponse(...blocks: string[]) {
  const encoder = new TextEncoder()
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const block of blocks) controller.enqueue(encoder.encode(block))
        controller.close()
      },
    }),
  )
}

// stream ends without stage_completed → error path fires
const incompleteErrors: string[] = []
const incompleteRunner = createSseRunner({
  onError: (d) => incompleteErrors.push(d.message),
})
globalThis.fetch = async () => sseResponse('event: progress\ndata: {"message":"working"}\n\n')
await incompleteRunner.run('/run')
assert.equal(incompleteErrors.length, 1)
assert.match(incompleteErrors[0], /ended before this stage completed/i)

// stream ends with stage_completed → no error, streaming flips true → false
const doneErrors: string[] = []
const streamingStates: boolean[] = []
const doneRunner = createSseRunner(
  { onError: (d) => doneErrors.push(d.message) },
  (streaming) => streamingStates.push(streaming),
)
globalThis.fetch = async () => sseResponse('event: stage_completed\ndata: {"stage":"S2"}\n\n')
await doneRunner.run('/run')
assert.deepEqual(doneErrors, [])
assert.deepEqual(streamingStates, [true, false])

// explicit error event keeps its own message (no second generic error)
const eventErrors: string[] = []
const eventRunner = createSseRunner({ onError: (d) => eventErrors.push(d.message) })
globalThis.fetch = async () => sseResponse('event: error\ndata: {"message":"Stage failed"}\n\n')
await eventRunner.run('/run')
assert.deepEqual(eventErrors, ['Stage failed'])

// unmount aborts the in-flight fetch and swallows the abort as an error
let aborted = false
const unmountErrors: string[] = []
globalThis.fetch = (_input, init) =>
  new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => {
      aborted = true
      reject(new DOMException('The operation was aborted.', 'AbortError'))
    })
  })
const unmountRunner = createSseRunner({ onError: (d) => unmountErrors.push(d.message) })
const pending = unmountRunner.run('/run')
unmountRunner.unmount()
await pending
assert.equal(aborted, true)
assert.deepEqual(unmountErrors, [])

console.log('useSse check: ok')
