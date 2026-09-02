package app.yxi

import app.yxi.agent.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 装机 / 探活的纯逻辑。样本是 2026-09-02 在干净的 ubuntu:24.04 容器里真跑出来的原文。 */
class SetupTest {

    @Test fun 探活_装了没登录() {
        val out = """
            __TMUX__
            tmux 3.4
            __CLAUDE__
            2.1.258 (Claude Code)
            {
              "loggedIn": false,
              "authMethod": "none"
            }
            __CODEX__
            codex-cli 0.152.1
            Not logged in
            __NODE__
            NO
            __OS__
            Ubuntu 24.04.4 LTS
            x86_64
            0
            __END__
        """.trimIndent()
        val p = Setup.parseProbe(out)!!
        assertEquals("3.4", p.tmux)
        assertEquals("2.1.258", p.claude)
        assertNull(p.claudeUser)
        assertEquals("0.152.1", p.codex)
        assertTrue(!p.codexLogged)
        assertNull(p.node)
        assertEquals("Ubuntu 24.04.4 LTS", p.os)
        assertEquals("x86_64", p.arch)
        assertTrue(p.root && !p.nothing)
    }

    @Test fun 探活_什么都没装() {
        val p = Setup.parseProbe("__TMUX__\nNO\n__CLAUDE__\nNO\n__CODEX__\nNO\n__NODE__\nNO\n__OS__\nDebian GNU/Linux 12\naarch64\n1000\n__END__\n")!!
        assertTrue(p.nothing && p.tmux == null && !p.root)
        assertEquals("aarch64", p.arch)
    }

    @Test fun 探活_登录了() {
        val p = Setup.parseProbe("__TMUX__\ntmux 3.4\n__CLAUDE__\n2.1.258 (Claude Code)\n{\"loggedIn\": true, \"email\": \"a@b.c\"}\n__CODEX__\ncodex-cli 0.152.1\nLogged in using ChatGPT\n__NODE__\nv18.19.1\n__OS__\nUbuntu\nx86_64\n0\n__END__")!!
        assertEquals("a@b.c", p.claudeUser)
        assertTrue(p.codexLogged)
        assertEquals("v18.19.1", p.node)
    }

    @Test fun 探活_半截输出当没探到() {
        assertNull(Setup.parseProbe("__TMUX__\ntmux 3.4\n__CLAUDE__"))
    }

    @Test fun 装机命令_只装一个() {
        val both = Setup.startCommand("#!/bin/bash\necho hi", true, true)
        assertTrue(!both.contains("YXI_NO_"))
        assertTrue(both.contains("<<'YXI_BOOTSTRAP_EOF'") && both.contains("echo __DONE__"))
        assertTrue(Setup.startCommand("#!x", claude = true, codex = false).contains("YXI_NO_CODEX=1 bash"))
        assertTrue(Setup.startCommand(null, claude = false, codex = true).contains("| YXI_NO_CLAUDE=1 bash"))
        // 退路那条要 pipefail：curl 都没有时管道右边的 bash 照样退出 0（#215）
        assertTrue(Setup.startCommand(null).contains("set -o pipefail"))
    }

    @Test fun 日志_结束判定() {
        assertNull(Setup.done("▶ 装 Codex"))
        assertEquals(true, Setup.done("装好了\n__DONE__0"))
        assertEquals(false, Setup.done("bash: wget: command not found\n__DONE__127"))
        assertTrue(Setup.running("▶ 装 Codex") && !Setup.running("") && !Setup.running("x\n__DONE__0"))
    }
}
