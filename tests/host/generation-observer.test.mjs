// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../runtime/android/generation-observer.js', import.meta.url), 'utf8');
function fixture() {
    const messages = [], handlers = new Map(), documentHandlers = new Map(), windowHandlers = new Map();
    const context = { streamingProcessor: null, eventTypes: Object.fromEntries([
        'GENERATION_STARTED', 'GENERATION_ENDED', 'GENERATION_STOPPED', 'GROUP_WRAPPER_STARTED', 'GROUP_WRAPPER_FINISHED', 'STREAM_TOKEN_RECEIVED'
    ].map(x => [x, x])), eventSource: {
        on(name, fn) { const list = handlers.get(name) ?? []; list.push(fn); handlers.set(name, list); },
        removeListener(name, fn) { handlers.set(name, (handlers.get(name) ?? []).filter(value => value !== fn)); },
    } };
    const document = { body: { dataset: {} }, visibilityState: 'visible',
        addEventListener: (name, fn) => documentHandlers.set(name, fn), removeEventListener: name => documentHandlers.delete(name) };
    const window = { SillyTavern: { getContext: () => context }, stShellActivity: { postMessage: text => messages.push(JSON.parse(text)) },
        addEventListener: (name, fn) => windowHandlers.set(name, fn), removeEventListener: name => windowHandlers.delete(name) };
    window.top = window;
    let tick, mutation;
    const sandbox = { window, document, setInterval: fn => { tick = fn; return 1; }, clearInterval: () => { tick = null; },
        MutationObserver: class { constructor(fn) { mutation = fn; } observe() {} disconnect() { mutation = null; } } };
    const environment = vm.createContext(sandbox);
    const install = token => vm.runInContext(source.replace('__ST_ANDROID_CONFIG__', JSON.stringify({ token })), environment);
    const emit = (name, ...args) => (handlers.get(name) ?? []).slice().forEach(fn => fn(...args));
    return { context, window, document, messages, handlers, install, emit, tick: () => tick?.(), mutate: () => mutation?.(), hide: () => windowHandlers.get('pagehide')?.() };
}

test('attempt/dry-run events do not turn idle UI into a generation lease', () => {
    const f = fixture(); f.install('a'.repeat(32));
    f.emit('GENERATION_STARTED', 'normal', { quiet_prompt: 'PRIVATE_PROMPT' }, true);
    const last = f.messages.at(-1);
    assert.equal(last.bodyBusy, false); assert.equal(last.streamBusy, false);
    assert(!JSON.stringify(f.messages).includes('PRIVATE_PROMPT'));
});
test('finished streaming remains busy until processor is cleared after save', () => {
    const f = fixture(); f.install('a'.repeat(32));
    f.document.body.dataset.generating = 'true'; f.mutate(); assert.equal(f.messages.at(-1).bodyBusy, true);
    f.context.streamingProcessor = { isFinished: true, isStopped: false };
    delete f.document.body.dataset.generating;
    f.emit('GENERATION_ENDED'); f.tick(); assert.equal(f.messages.at(-1).streamBusy, true);
    f.context.streamingProcessor = null; f.tick(); assert.equal(f.messages.at(-1).streamBusy, false);
});
test('cancelled stream and completed group release observed work; token events carry no text', () => {
    const f = fixture(); f.install('a'.repeat(32));
    f.emit('GROUP_WRAPPER_STARTED', { selected_group: 'PRIVATE_CHAT_NAME' });
    assert.equal(f.messages.at(-1).groupBusy, true);
    f.emit('STREAM_TOKEN_RECEIVED', 'PRIVATE_TOKEN'); f.document.visibilityState = 'hidden'; f.tick();
    assert.equal(f.messages.at(-1).streamEvents, 1);
    f.context.streamingProcessor = { isStopped: true }; f.emit('GROUP_WRAPPER_FINISHED'); f.tick();
    assert.equal(f.messages.at(-1).groupBusy, false); assert.equal(f.messages.at(-1).streamBusy, false);
    assert(!JSON.stringify(f.messages).includes('PRIVATE'));
});
test('observer install is idempotent and detaches listeners on navigation', () => {
    const f = fixture(); f.install('a'.repeat(32)); f.install('a'.repeat(32));
    assert.equal(f.handlers.get('GENERATION_STARTED').length, 1);
    f.install('b'.repeat(32)); assert.equal(f.handlers.get('GENERATION_STARTED').length, 1);
    f.hide(); assert.equal(f.messages.at(-1).ready, false); assert.equal(f.handlers.get('GENERATION_STARTED').length, 0);
});
test('subframes receive no observer or native activity messages', () => {
    const f = fixture(); f.window.top = {}; f.install('a'.repeat(32));
    assert.equal(f.messages.length, 0); assert.equal(f.handlers.size, 0);
});
