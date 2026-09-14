// SPDX-License-Identifier: AGPL-3.0-only
import { parentPort, workerData } from 'node:worker_threads';
parentPort.postMessage({ answer: workerData * 2, platform: process.platform, arch: process.arch });
