// SPDX-License-Identifier: AGPL-3.0-only
// Local git smart-HTTP fixture for a REAL upstream extension install request.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { once } from 'node:events';
import { spawn, execFileSync } from 'node:child_process';

export async function gitFixture(root) {
    const source = path.join(root, 'regression-extension');
    fs.mkdirSync(source);
    fs.writeFileSync(path.join(source, 'manifest.json'), JSON.stringify({ display_name: 'Regression fixture',
        loading_order: 1, requires: [], optional: [], js: 'index.js', css: '', author: 'test', version: '1.0.0' }));
    fs.writeFileSync(path.join(source, 'index.js'), '// deterministic test extension\n');
    for (const args of [['init', '-q', '-b', 'main'], ['add', '.'],
        ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture']]) {
        execFileSync('git', args, { cwd: source, stdio: 'ignore' });
    }
    execFileSync('git', ['clone', '--bare', source, path.join(root, 'regression-extension.git')], { stdio: 'ignore' });
    const service = http.createServer((req, res) => {
        const url = new URL(req.url, 'http://fixture.invalid');
        if (!url.pathname.startsWith('/regression-extension.git/')) { res.writeHead(404); res.end(); return; }
        const child = spawn('git', ['http-backend'], { env: { ...process.env, GIT_PROJECT_ROOT: root,
            GIT_HTTP_EXPORT_ALL: '1', PATH_INFO: url.pathname, QUERY_STRING: url.search.slice(1),
            REQUEST_METHOD: req.method, CONTENT_TYPE: req.headers['content-type'] || '', REMOTE_USER: 'fixture' } });
        req.pipe(child.stdin); child.stdin.on('error', () => {});
        let buffer = Buffer.alloc(0), headers = true;
        child.stdout.on('data', chunk => {
            if (!headers) { res.write(chunk); return; }
            buffer = Buffer.concat([buffer, chunk]);
            const boundary = buffer.indexOf('\r\n\r\n');
            if (boundary < 0) return;
            for (const line of buffer.subarray(0, boundary).toString().split('\r\n')) {
                const index = line.indexOf(':');
                if (index > 0) res.setHeader(line.slice(0, index), line.slice(index + 1).trim());
            }
            headers = false; res.write(buffer.subarray(boundary + 4));
        });
        child.stdout.on('end', () => res.end()); child.stderr.resume();
        child.on('error', () => { res.statusCode = 500; res.end(); });
    });
    service.listen(0, '127.0.0.1'); await once(service, 'listening');
    return { url: `http://127.0.0.1:${service.address().port}/regression-extension.git`,
        async close() { service.closeAllConnections(); await new Promise(resolve => service.close(resolve)); } };
}
