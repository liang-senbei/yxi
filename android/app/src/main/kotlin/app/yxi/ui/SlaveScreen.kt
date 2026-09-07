package app.yxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.yxi.agent.Slave
import app.yxi.agent.Tz
import app.yxi.ssh.SshSession

private val Pill = RoundedCornerShape(100.dp)

/**
 * **从机页** —— 从主机跳一层，看另一台机器上活着的会话（老板 2026-09-07）。
 *
 * ⚠️ **连不上时不许只说「连不上」。** 这一层的失败长得都一样，但修法完全不同
 * （tailnet 的访问策略挡了 TCP / 主机的公钥没装进从机 / 那台机器没开 SSH），
 * 所以这里照 [Slave.Result.Trouble] 把「什么现象 · 为什么 · 怎么办」摆出来，能给命令的给命令。
 * 见 [Slave] 的类注释。
 */
@Composable
fun SlaveScreen(
    /** 等一条活着的连接（[app.yxi.ui.rememberAliveSsh]）—— 不是一个抓好的 session，理由见 [Slave.probe] */
    alive: suspend (Long) -> SshSession?,
    /** 从机在**主机上**的 SSH 目标：ssh_config 别名，或 user@100.x */
    target: String,
    /** 显示用的名字（内网设备列表里那个主机名） */
    title: String,
    tailscaleIp: String = "",
    /** 点一个会话 = 在从机上接上它（终端走主机跳过去） */
    onOpenSession: (name: String, attachCommand: String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var result by remember(target) { mutableStateOf<Slave.Result?>(null) }
    var tick by remember(target) { mutableIntStateOf(0) }

    LaunchedEffect(target, tick) {
        result = null
        result = Slave.probe(alive, target, tailscaleIp)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                modifier = Modifier.clip(Pill).clickable(onClick = onBack)) {
                Text("←", Modifier.padding(15.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    t("从机 · 经主机连过去"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = Pill,
                modifier = Modifier.clip(Pill).clickable { tick++ }) {
                Text(t("重试"), Modifier.padding(15.dp, 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }

        when (val r = result) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }

            is Slave.Result.Trouble -> LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Section(t("怎么了"), r.what, tone = MaterialTheme.colorScheme.error) }
                item { Section(t("为什么"), r.why) }
                item { Section(t("怎么办"), r.how) }
                if (r.cmd.isNotBlank()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("照这个来"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("slave", r.cmd))
                                    android.widget.Toast.makeText(ctx, t("复制好了"), android.widget.Toast.LENGTH_SHORT).show()
                                },
                        ) {
                            Text(
                                r.cmd, Modifier.padding(14.dp, 12.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                        Text(t("点一下复制"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            is Slave.Result.Ok -> when {
                !r.hasTmux -> Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("连上了，但这台机器上没有 tmux"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            t("会话是 tmux 开的 —— 没有它就没有会话可看。装上之后这里就会有了。"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                r.sessions.isEmpty() -> Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("连上了，它上面还没有会话"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            t("在那台机器上开一个 tmux 会话，这里就能看到。"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            t("%d 个会话 · 点一个接上去").format(r.sessions.size),
                            Modifier.padding(4.dp, 2.dp, 4.dp, 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    items@ for (s in r.sessions) item(key = s.name) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .clickable { onOpenSession(s.name, Slave.attachCommand(target, s.name)) },
                        ) {
                            Row(Modifier.padding(16.dp, 13.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier.size(8.dp).clip(CircleShape)
                                        .background(
                                            if (s.attached) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text(
                                        s.cwd.ifBlank { s.command },
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.outline, maxLines = 1,
                                    )
                                }
                                if (s.activity > 0) Text(
                                    Tz.stamp(s.activity),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(label: String, body: String, tone: androidx.compose.ui.graphics.Color? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = tone ?: MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(2.dp))
}
