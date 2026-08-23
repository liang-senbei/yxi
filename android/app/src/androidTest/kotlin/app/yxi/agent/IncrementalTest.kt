package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量解析必须跟一次性解析**结果一模一样**。
 *
 * ⚠️ 这条是整个增量改造的地基。老做法是每 300ms 把整个缓冲从头重解 ——
 * 实测真实会话 `tail -n 800` 是 4.17 MB，**每秒重嚼三次 4 MB**。
 * 改成增量之后，唯一要保证的就是「分批喂 == 一次喂」。见 TROUBLESHOOTING #86。
 *
 * 样例是从真实转录里连续抠的，含 tool_use + tool_result（**跨行回填**那条路）。
 */
class IncrementalTest {

    @Test fun 一行一行喂和一次喂结果相同() {
        val once = Transcript.parse(REAL.asSequence())
        val inc = Transcript.Incremental()
        REAL.forEach { inc.add(sequenceOf(it)) }
        val step = inc.snapshot()
        assertEquals("条数对不上", once.size, step.size)
        assertEquals(
            "内容对不上：\n一次=" + once.map { it::class.simpleName + ":" + it.key } +
                "\n分批=" + step.map { it::class.simpleName + ":" + it.key },
            once.map { it.key to it::class.simpleName },
            step.map { it.key to it::class.simpleName },
        )
    }

    @Test fun 工具结果跨批回填时会换成新实例() {
        // ⚠️ **这条防的是「Compose 看不见」**：ToolCall 里几个字段是 var，
        // 就地改的话列表里还是同一个对象、key 也没变，那张卡会永远停在「进行中」。
        // 所以回填必须整条替换成新实例。
        val inc = Transcript.Incremental()
        inc.add(sequenceOf(USE))
        val before = inc.snapshot().filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("这时候还没有结果", null, before.result)
        inc.add(sequenceOf(RESULT))
        val after = inc.snapshot().filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("回填之后 key 不该变（Compose 靠它认同一张卡）", before.key, after.key)
        assertTrue("回填之后应该有结果了", after.result != null)
        assertTrue("回填之后还是同一个实例 —— Compose 不会重绘", before !== after)
    }

    private companion object {
        /** 一对**真正配对**的 tool_use / tool_result（真实转录抠的） */
        const val USE = "{\"parentUuid\":\"36c9c948-fe9c-43cd-9bf3-88d2b02d86f4\",\"isSidechain\":false,\"message\":{\"model\":\"claude-opus-5\",\"id\":\"msg_011CeHKFQGRXW4ZokBU3PxbN\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_016LjH4Q2jr8vUrrc8263de5\",\"name\":\"ToolSearch\",\"input\":{\"query\":\"web search fetch url\",\"max_results\":6},\"caller\":{\"type\":\"direct\"}}],\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"stop_details\":null,\"usage\":{\"input_tokens\":2,\"cache_creation_input_tokens\":10570,\"cache_read_input_tokens\":26479,\"output_tokens\":1063,\"output_tokens_details\":{\"thinking_tokens\":763},\"server_tool_use\":{\"web_search_requests\":0,\"web_fetch_requests\":0},\"service_tier\":\"standard\",\"cache_creation\":{\"ephemeral_1h_input_tokens\":10570,\"ephemeral_5m_input_tokens\":0},\"inference_geo\":\"not_available\",\"iterations\":[{\"input_tokens\":2,\"output_tokens\":1063,\"cache_read_input_tokens\":26479,\"cache_creation_input_tokens\":10570,\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":10570},\"type\":\"message\"}],\"speed\":\"standard\"},\"diagnostics\":null},\"requestId\":\"req_011CeHKFP3Fs1DWLeoTSPmjf\",\"type\":\"assistant\",\"uuid\":\"819439c8-f680-46bd-9299-65547d9f393b\",\"timestamp\":\"2026-08-22T06:30:00.709Z\",\"effort\":\"max\",\"session_id\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}"
        const val RESULT = "{\"parentUuid\":\"819439c8-f680-46bd-9299-65547d9f393b\",\"isSidechain\":false,\"promptId\":\"d44d26eb-e4a4-472a-9ea0-f102ca31e859\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_016LjH4Q2jr8vUrrc8263de5\",\"content\":[{\"type\":\"tool_reference\",\"tool_name\":\"WebFetch\"},{\"type\":\"tool_reference\",\"tool_name\":\"WebSearch\"},{\"type\":\"tool_reference\",\"tool_name\":\"RemoteTrigger\"},{\"type\":\"tool_reference\",\"tool_name\":\"ExitPlanMode\"},{\"type\":\"tool_reference\",\"tool_name\":\"Monitor\"}]}]},\"uuid\":\"11fa9d28-5b11-4fe0-acaf-baf9110b68a5\",\"timestamp\":\"2026-08-22T06:30:00.735Z\",\"toolUseResult\":{\"matches\":[\"WebFetch\",\"WebSearch\",\"RemoteTrigger\",\"ExitPlanMode\",\"Monitor\"],\"query\":\"web search fetch url\",\"total_deferred_tools\":18},\"sourceToolAssistantUUID\":\"819439c8-f680-46bd-9299-65547d9f393b\",\"session_id\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}"

        /** 真实转录里连续抠的一段 */
        val REAL = listOf(
            "{\"type\":\"last-prompt\",\"leafUuid\":\"61d96282-c460-48a5-b408-f90b1ad5b18f\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\"}",
            "{\"type\":\"mode\",\"mode\":\"normal\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\"}",
            "{\"type\":\"permission-mode\",\"permissionMode\":\"bypassPermissions\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\"}",
            "{\"type\":\"atis-latch\",\"atis\":\"\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\"}",
            "{\"parentUuid\":\"7f732461-b667-40e0-b6f5-0ea4284c50f1\",\"isSidechain\":false,\"type\":\"attachment\",\"uuid\":\"61d96282-c460-48a5-b408-f90b1ad5b18f\",\"timestamp\":\"2026-08-22T06:29:45.374Z\",\"attachment\":{\"type\":\"goal_status\",\"met\":false,\"sentinel\":true,\"condition\":\"你应该知道Moshi这个软件就是手机上也能ssh进去Linux服务器并且能够做到手机管理这么多tmux的Linux项目，然后给Linux的cc下指令的，我想让你复刻一个，你先研究一下他这个是怎么做的，你别直接做先给我一个prd文档和计划文档\"},\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}",
            "{\"type\":\"file-history-snapshot\",\"messageId\":\"858b213f-2c2f-42da-9b89-bf727bcb2fe4\",\"snapshot\":{\"messageId\":\"858b213f-2c2f-42da-9b89-bf727bcb2fe4\",\"trackedFileBackups\":{},\"timestamp\":\"2026-08-22T06:29:45.854Z\"},\"isSnapshotUpdate\":false}",
            "{\"parentUuid\":\"61d96282-c460-48a5-b408-f90b1ad5b18f\",\"isSidechain\":false,\"promptId\":\"d44d26eb-e4a4-472a-9ea0-f102ca31e859\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"<command-name>/goal</command-name>\\n            <command-message>goal</command-message>\\n            <command-args>你应该知道Moshi这个软件就是手机上也能ssh进去Linux服务器并且能够做到手机管理这么多tmux的Linux项目，然后给Linux的cc下指令的，我想让你复刻一个，你先研究一下他这个是怎么做的，你别直接做先给我一个prd文档和计划文档</command-args>\"},\"uuid\":\"858b213f-2c2f-42da-9b89-bf727bcb2fe4\",\"timestamp\":\"2026-08-22T06:29:45.375Z\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}",
            "{\"parentUuid\":\"858b213f-2c2f-42da-9b89-bf727bcb2fe4\",\"isSidechain\":false,\"promptId\":\"d44d26eb-e4a4-472a-9ea0-f102ca31e859\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"<local-command-stdout>Goal set: 你应该知道Moshi这个软件就是手机上也能ssh进去Linux服务器并且能够做到手机管理这么多tmux的Linux项目，然后给Linux的cc下指令的，我想让你复刻一个，你先研究一下他这个是怎么做的，你别直接做先给我一个prd文档和计划文档</local-command-stdout>\"},\"uuid\":\"d8b33b23-8dc2-4d5f-8617-403d7d1ab706\",\"timestamp\":\"2026-08-22T06:29:45.375Z\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}",
            "{\"parentUuid\":\"d8b33b23-8dc2-4d5f-8617-403d7d1ab706\",\"isSidechain\":false,\"promptId\":\"d44d26eb-e4a4-472a-9ea0-f102ca31e859\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"A session-scoped Stop hook is now active with condition: \\\"你应该知道Moshi这个软件就是手机上也能ssh进去Linux服务器并且能够做到手机管理这么多tmux的Linux项目，然后给Linux的cc下指令的，我想让你复刻一个，你先研究一下他这个是怎么做的，你别直接做先给我一个prd文档和计划文档\\\". Briefly acknowledge the goal, then immediately start (or continue) working toward it — treat the condition itself as your directive and do not pause to ask the user what to do. The hook will block stopping until the condition holds. It auto-clears once the condition is met — do not tell the user to run `/goal clear` after success; that's only for clearing a goal early.\"},\"isMeta\":true,\"uuid\":\"057b6391-f9bd-45e3-b7a9-0047667ff6b4\",\"timestamp\":\"2026-08-22T06:29:45.375Z\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}",
            "{\"parentUuid\":\"5c7a85fc-c7df-475c-a82e-53d34b676d78\",\"isSidechain\":false,\"message\":{\"model\":\"claude-opus-5\",\"id\":\"msg_011CeHKFQGRXW4ZokBU3PxbN\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"我先摸清两件事:Moshi 到底怎么实现的、以及你这台机器现有的 `hub`/tmux 底子能复用多少。先并行开工。\"}],\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"stop_details\":null,\"usage\":{\"input_tokens\":2,\"cache_creation_input_tokens\":10570,\"cache_read_input_tokens\":26479,\"output_tokens\":1063,\"output_tokens_details\":{\"thinking_tokens\":763},\"server_tool_use\":{\"web_search_requests\":0,\"web_fetch_requests\":0},\"service_tier\":\"standard\",\"cache_creation\":{\"ephemeral_1h_input_tokens\":10570,\"ephemeral_5m_input_tokens\":0},\"inference_geo\":\"not_available\",\"iterations\":[{\"input_tokens\":2,\"output_tokens\":1063,\"cache_read_input_tokens\":26479,\"cache_creation_input_tokens\":10570,\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":10570},\"type\":\"message\"}],\"speed\":\"standard\"},\"diagnostics\":null},\"requestId\":\"req_011CeHKFP3Fs1DWLeoTSPmjf\",\"type\":\"assistant\",\"uuid\":\"36c9c948-fe9c-43cd-9bf3-88d2b02d86f4\",\"timestamp\":\"2026-08-22T06:29:59.783Z\",\"effort\":\"max\",\"session_id\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}",
            "{\"parentUuid\":\"36c9c948-fe9c-43cd-9bf3-88d2b02d86f4\",\"isSidechain\":false,\"message\":{\"model\":\"claude-opus-5\",\"id\":\"msg_011CeHKFQGRXW4ZokBU3PxbN\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_016LjH4Q2jr8vUrrc8263de5\",\"name\":\"ToolSearch\",\"input\":{\"query\":\"web search fetch url\",\"max_results\":6},\"caller\":{\"type\":\"direct\"}}],\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"stop_details\":null,\"usage\":{\"input_tokens\":2,\"cache_creation_input_tokens\":10570,\"cache_read_input_tokens\":26479,\"output_tokens\":1063,\"output_tokens_details\":{\"thinking_tokens\":763},\"server_tool_use\":{\"web_search_requests\":0,\"web_fetch_requests\":0},\"service_tier\":\"standard\",\"cache_creation\":{\"ephemeral_1h_input_tokens\":10570,\"ephemeral_5m_input_tokens\":0},\"inference_geo\":\"not_available\",\"iterations\":[{\"input_tokens\":2,\"output_tokens\":1063,\"cache_read_input_tokens\":26479,\"cache_creation_input_tokens\":10570,\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":10570},\"type\":\"message\"}],\"speed\":\"standard\"},\"diagnostics\":null},\"requestId\":\"req_011CeHKFP3Fs1DWLeoTSPmjf\",\"type\":\"assistant\",\"uuid\":\"819439c8-f680-46bd-9299-65547d9f393b\",\"timestamp\":\"2026-08-22T06:30:00.709Z\",\"effort\":\"max\",\"session_id\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}",
            "{\"parentUuid\":\"819439c8-f680-46bd-9299-65547d9f393b\",\"isSidechain\":false,\"promptId\":\"d44d26eb-e4a4-472a-9ea0-f102ca31e859\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_016LjH4Q2jr8vUrrc8263de5\",\"content\":[{\"type\":\"tool_reference\",\"tool_name\":\"WebFetch\"},{\"type\":\"tool_reference\",\"tool_name\":\"WebSearch\"},{\"type\":\"tool_reference\",\"tool_name\":\"RemoteTrigger\"},{\"type\":\"tool_reference\",\"tool_name\":\"ExitPlanMode\"},{\"type\":\"tool_reference\",\"tool_name\":\"Monitor\"}]}]},\"uuid\":\"11fa9d28-5b11-4fe0-acaf-baf9110b68a5\",\"timestamp\":\"2026-08-22T06:30:00.735Z\",\"toolUseResult\":{\"matches\":[\"WebFetch\",\"WebSearch\",\"RemoteTrigger\",\"ExitPlanMode\",\"Monitor\"],\"query\":\"web search fetch url\",\"total_deferred_tools\":18},\"sourceToolAssistantUUID\":\"819439c8-f680-46bd-9299-65547d9f393b\",\"session_id\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/root/src/workspace/Yxi\",\"sessionId\":\"d0ccc7db-ab52-458f-807f-39247666d0c2\",\"version\":\"2.1.239\",\"gitBranch\":\"HEAD\"}"
        )
    }
}
