// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { treeInventory, verifyInstallation, extractSource } from '../../runtime/mobile/first-install.mjs';

const sha = data => crypto.createHash('sha256').update(data).digest('hex');
// Deliberately requires built installer, never substitutes an invented tar library.
const pointer = JSON.parse(fs.readFileSync(new URL('../../build/installer/current.json', import.meta.url)));
const artifact = new URL(`../../build/installer/${pointer.payloadId}/`, import.meta.url);
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'installer-unit-'));
execFileSync('python3', ['-c', 'import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])', new URL('payload.zip', artifact).pathname, work]);
const require = createRequire(path.join(work, 'shell/npm/package.json'));
const tar = require('tar');
process.on('exit', () => fs.rmSync(work, { recursive: true, force: true }));

test('downloaded content: same-size tamper is rejected, irrespective of read-only mode', async () => {
    const root = fs.mkdtempSync(path.join(work, 'tree-'));
    fs.mkdirSync(path.join(root, 'server'));
    const file = path.join(root, 'server/server.js');
    fs.writeFileSync(file, 'original');
    const manifest = { schemaVersion: 1, payloadId: 'a'.repeat(64), ...await treeInventory(root) };
    const bytes = JSON.stringify(manifest);
    fs.writeFileSync(path.join(root, 'payload-manifest.json'), bytes);
    await verifyInstallation(root, sha(bytes));
    fs.writeFileSync(file, 'tampered'); fs.chmodSync(file, 0o400);
    await assert.rejects(verifyInstallation(root, sha(bytes)), /content changed/);
});

test('unexpected file, directory, symlink and changed manifest are rejected', async () => {
    const root = fs.mkdtempSync(path.join(work, 'tree-'));
    fs.writeFileSync(path.join(root, 'source'), 'ok');
    const bytes = JSON.stringify({ ...await treeInventory(root) });
    fs.writeFileSync(path.join(root, 'payload-manifest.json'), bytes);
    fs.writeFileSync(path.join(root, 'extra'), 'bad');
    await assert.rejects(verifyInstallation(root, sha(bytes)), /content changed/);
    fs.unlinkSync(path.join(root, 'extra'));
    fs.mkdirSync(path.join(root, 'extra'));
    await assert.rejects(verifyInstallation(root, sha(bytes)), /directories changed/);
    fs.rmdirSync(path.join(root, 'extra'));
    fs.symlinkSync('/tmp', path.join(root, 'extra'));
    await assert.rejects(verifyInstallation(root, sha(bytes)), /symlink/);
    fs.unlinkSync(path.join(root, 'extra'));
    fs.appendFileSync(path.join(root, 'payload-manifest.json'), ' ');
    await assert.rejects(verifyInstallation(root, sha(bytes)), /manifest changed/);
});

for (const kind of ['traversal', 'symlink', 'hardlink', 'duplicate', 'mixed-root']) {
    test(`GitHub archive rejects ${kind}`, async () => {
        const archive = path.join(work, kind + '.tgz');
        execFileSync('python3', ['-c', `import tarfile,io,sys
with tarfile.open(sys.argv[1],'w:gz') as t:
 def add(name,kind=tarfile.REGTYPE):
  i=tarfile.TarInfo(name);i.type=kind;i.size=0;i.linkname='/tmp/outside';t.addfile(i,io.BytesIO())
 kind=sys.argv[2]
 if kind=='traversal': add('root/../../outside')
 elif kind=='symlink': add('root/link',tarfile.SYMTYPE)
 elif kind=='hardlink': add('root/link',tarfile.LNKTYPE)
 elif kind=='duplicate': add('root/file');add('root/file')
 else: add('first/file');add('second/file')`, archive, kind]);
        await assert.rejects(extractSource(tar, archive, path.join(work, kind)));
    });
}

test('corrupted pointer file is quarantined and does not crash or block atomic writes', async () => {
    const base = fs.mkdtempSync(path.join(work, 'quarantine-'));
    const pointerPath = path.join(base, 'current.json');
    fs.writeFileSync(pointerPath, '{ broken json');
    assert.throws(() => JSON.parse(fs.readFileSync(pointerPath, 'utf8')));
    // Verify symlink pointer is detected
    const symlinkPointer = path.join(base, 'symlink-pointer.json');
    fs.symlinkSync('/tmp', symlinkPointer);
    assert(fs.lstatSync(symlinkPointer).isSymbolicLink());
});
