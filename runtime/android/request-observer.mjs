// SPDX-License-Identifier: AGPL-3.0-only
// Observation only: no request/response wrapping, route changes, body reads or retries.
import { channel } from 'node:diagnostics_channel';
import fs from 'node:fs';

const generationPaths = new Set([
    '/api/backends/chat-completions/generate', '/api/backends/text-completions/generate',
    '/api/backends/kobold/generate', '/api/novelai/generate', '/api/horde/generate-text',
]);
const savePaths = new Set(['/api/chats/save', '/api/chats/group/save']);

export function observeRequests({ port, output, publish }) {
    const state = { schemaVersion: 1, pid: process.pid, sequence: 0,
        activeGeneration: 0, activeSave: 0, generationStarted: 0, generationFinished: 0,
        generationDisconnected: 0, generationFailed: 0, saveStarted: 0, saveFinished: 0,
        saveDisconnected: 0, saveFailed: 0, writeErrors: 0 };
    const write = () => {
        try {
            const snapshot = { ...state, updatedAt: Date.now() };
            if (publish) publish(snapshot);
            else {
                const temporary = `${output}.tmp`;
                fs.writeFileSync(temporary, JSON.stringify(snapshot), { mode: 0o600 });
                fs.renameSync(temporary, output);
            }
        } catch { state.writeErrors++; } // Never change HTTP behavior because diagnostics failed.
    };
    const incoming = ({ request, response }) => {
        try {
            if (request.method !== 'POST' || request.socket.localPort !== port) return;
            const pathname = new URL(request.url, 'http://loopback.invalid').pathname;
            const kind = generationPaths.has(pathname) ? 'generation' : savePaths.has(pathname) ? 'save' : null;
            if (!kind) return;
            const active = kind === 'generation' ? 'activeGeneration' : 'activeSave';
            state[active]++;
            state[`${kind}Started`]++;
            state.sequence++;
            write();
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                state[active] = Math.max(0, state[active] - 1);
                const status = response.statusCode;
                if (!response.writableFinished) state[`${kind}Disconnected`]++;
                else if (status < 200 || status >= 300) state[`${kind}Failed`]++;
                else state[`${kind}Finished`]++;
                state.sequence++;
                write();
            };
            response.once('finish', finish);
            response.once('close', finish);
        } catch { state.writeErrors++; }
    };
    const requests = channel('http.server.request.start');
    requests.subscribe(incoming);
    write();
    return { snapshot: () => ({ ...state }), dispose: () => requests.unsubscribe(incoming) };
}
