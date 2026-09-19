package com.jcraft.jsch

/**
 * 仅测试：假 codex 进程走管道，`SshSession.Shell` 只在 resize/close/isConnected 时摸通道。
 * 放回 jsch 包是因为 `Channel` 的构造器是包私有；生产路径不经此类。
 */
internal class NullChannel : Channel() {
    override fun run() {}
}
