package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {

    /**
     * ⚠️ 目录名规则是**实测**出来的，不是猜的：本机 `~/.claude/projects/` 下真的存在
     * `-opt-workspace-----` 这种目录（对应 `/opt/workspace/日常对话`，四个汉字四个横杠）。
     * 一开始只把斜杠换成横杠，中文路径的会话就永远「找不到转录」。
     */
    @Test fun 项目目录名() {
        assertEquals("-root-src-workspace-Yxi", Transcript.projectDirOf("/root/src/workspace/Yxi"))
        assertEquals("-tmp", Transcript.projectDirOf("/tmp"))
        // 汉字：一个字一个横杠
        assertEquals("-opt-workspace-----", Transcript.projectDirOf("/opt/workspace/日常对话"))
        assertEquals("-opt-workspace---", Transcript.projectDirOf("/opt/workspace/环境"))
        // 点、空格、横杠本身也都变横杠
        assertEquals("-root--claude", Transcript.projectDirOf("/root/.claude"))
        assertEquals("-a-b-c", Transcript.projectDirOf("/a b.c"))
        assertEquals("-x-y", Transcript.projectDirOf("/x-y"))
    }
}
