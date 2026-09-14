// SPDX-License-Identifier: AGPL-3.0-only
package dev.stshell.probe

internal object NativeNode {
    init { System.loadLibrary("stprobe") }
    external fun start(arguments: Array<String>): Int
}
