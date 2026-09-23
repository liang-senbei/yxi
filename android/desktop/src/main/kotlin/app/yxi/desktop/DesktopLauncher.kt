package app.yxi.desktop

import app.yxi.agent.Dirs
import app.yxi.agent.PermissionMode
import app.yxi.ssh.Shell
import java.util.UUID

data class DesktopLaunchPlan(val directory: String, val agent: String, val requestId: String, val initialPrompt: String = "", val collaborationGroup: String = "", val isolatedWorktree: Boolean = false,
    val permissionMode: PermissionMode? = null, val mcpConfigPath: String? = null, val mcpConfigHash: String? = null, val codexMcpArguments: List<String> = emptyList()) {
    init {
        require(RunnerCatalog.find(agent)?.serverCreation == true) { "此运行器的会话创建适配尚未完成" }
        require(directory.startsWith('/') && directory.none { it < ' ' || it == '\u007f' }) { "请输入服务器上的绝对路径，不含控制字符" }
        require(Regex("[a-f0-9]{32}").matches(requestId)) { "启动请求标识无效" }
        require(initialPrompt.length <= 16000 && '\u0000' !in initialPrompt) { "启动提示词最多 16000 字符，不能包含空字符" }
        require((mcpConfigPath == null) == (mcpConfigHash == null))
        if (mcpConfigPath != null) require(agent == "claude" && mcpConfigPath.startsWith('/') && mcpConfigPath.none { it < ' ' } && Regex("[a-f0-9]{64}").matches(mcpConfigHash.orEmpty()))
        require(codexMcpArguments.isEmpty() || agent == "codex")
        require(codexMcpArguments.size % 2 == 0 && codexMcpArguments.chunked(2).all { it[0] == "-c" && Regex("^mcp_servers\\.[A-Za-z0-9_-]+=").containsMatchIn(it[1]) })
        require(codexMcpArguments.sumOf { it.toByteArray().size } <= 65536 && codexMcpArguments.none { '\u0000' in it }) { "共享 MCP 启动参数过大或包含空字符" }
    }
    val sessionName: String get() {
        val slug = directory.trimEnd('/').substringAfterLast('/').map { if (it.isLetterOrDigit() || it in "_-") it else '_' }.joinToString("").take(40).ifBlank { "workspace" }
        return (if (agent == "codex") "cx-" else "cc-") + slug + "-" + requestId
    }
    fun command(): String = command(false)
    internal fun preparationCommand(): String = command(true)
    private fun command(prepareOnly: Boolean): String {
        val tag = Dirs.TAG
        require(permissionMode == null || agent == "claude") { "此权限模式仅适用于 Claude Code" }
        // Native root bypass requires this compatibility flag; it does not create a sandbox.
        val execPrefix = if (permissionMode == PermissionMode.Bypass) "exec env IS_SANDBOX=1" else "exec"
        val launchArguments = permissionMode?.let { listOf("--permission-mode", it.nativeId) }.orEmpty() +
            mcpConfigPath?.let { listOf("--mcp-config", it) }.orEmpty() + codexMcpArguments
        val quotedArguments = launchArguments.joinToString(" ") { Shell.q(it) }
        val checkMcp = if (mcpConfigPath == null) "" else """
mcp_status=${'$'}(python3 -c ${Shell.q(RemoteClaudeSharedMcp.preflightScript)} ${Shell.q(mcpConfigPath)} ${Shell.q(mcpConfigHash!!)} "${'$'}bin" "${'$'}want")
case "${'$'}mcp_status" in ready) ;; mcp-conflict|mcp-config-invalid|mcp-config-changed|mcp-check-failed) echo "$tag:${'$'}mcp_status"; exit 0;; *) echo '$tag:mcp-check-failed'; exit 0;; esac
""".trimIndent()
        val prepareDirectory = if (!isolatedWorktree) "mkdir -p -- \"${'$'}d\" 2>/dev/null || { echo '$tag:nodir'; exit 0; }" else {
            val script = """
import pathlib, subprocess, sys
source, request = sys.argv[1:]
def git(path, *args):
    return subprocess.check_output(['git', '-C', str(path), *args], text=True).strip()
root = pathlib.Path(git(source, 'rev-parse', '--show-toplevel')).resolve()
target = root.parent / (root.name + '-yxi-' + request)
if target.is_symlink(): raise ValueError('独立工作目录不能是符号链接')
if target.exists():
    if not (target / '.git').is_file(): raise ValueError('目标目录已存在且不是独立工作树')
    original = pathlib.Path(git(root, 'rev-parse', '--path-format=absolute', '--git-common-dir')).resolve()
    actual = pathlib.Path(git(target, 'rev-parse', '--path-format=absolute', '--git-common-dir')).resolve()
    if original != actual: raise ValueError('已有工作树属于其他仓库')
else:
    subprocess.run(['git', '-C', str(root), 'worktree', 'add', '--detach', str(target), 'HEAD'], check=True, stdout=sys.stderr)
print(target)
""".trimIndent()
            "d=${'$'}(python3 -c ${Shell.q(script)} ${Shell.q(directory)} ${Shell.q(requestId)}) || { echo '$tag:worktree-failed'; exit 0; }"
        }
        val joinGroup = if (collaborationGroup.isBlank()) "" else {
            val script = """
import json, os, pathlib, sys, tempfile
group, member = sys.argv[1:]
p = pathlib.Path.home() / '.yxi' / 'groups.json'
original = p.read_bytes()
data = json.loads(original)
members = data['groups'].get(group)
if not isinstance(members, list): raise ValueError('协作组已不存在或格式无效')
if member not in members:
    members.append(member)
    fd, name = tempfile.mkstemp(prefix='groups-launch-', dir=p.parent)
    try:
        with os.fdopen(fd, 'w') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.flush(); os.fsync(f.fileno())
        if p.read_bytes() != original: raise ValueError('分组已变化，请重试')
        os.replace(name, p)
    finally:
        if os.path.exists(name): os.unlink(name)
""".trimIndent()
            "python3 -c ${Shell.q(script)} ${Shell.q(collaborationGroup)} ${Shell.q(sessionName)} || { echo '$tag:group-failed'; exit 0; }"
        }
        return """
d=${Shell.q(directory)}; n=${Shell.q(sessionName)}; agent=${Shell.q(agent)}; prompt=${Shell.q(initialPrompt)}
command -v tmux >/dev/null 2>&1 || { echo '$tag:missing-tmux'; exit 0; }
${RunnerCatalog.resolveCommand(agent)}
[ -f "${'$'}bin" ] && [ -x "${'$'}bin" ] || { echo '$tag:missing-runtime'; exit 0; }
$prepareDirectory
want=${'$'}(cd -- "${'$'}d" 2>/dev/null && pwd -P) || { echo '$tag:nodir'; exit 0; }
${if (prepareOnly) "printf '%s\\n' \"$tag:prepared:${'$'}want\"; exit 0" else ""}
if tmux has-session -t "=${'$'}n" 2>/dev/null; then
  got=${'$'}(tmux display-message -p -t "=${'$'}n:" '#{pane_current_path}' 2>/dev/null)
  [ "${'$'}got" = "${'$'}want" ] || { echo '$tag:conflict'; exit 0; }
  echo '$tag:exists'; exit 0
fi
$checkMcp
$joinGroup
if [ -n "${'$'}prompt" ]; then
  tmux new-session -d -s "${'$'}n" -c "${'$'}want" /bin/sh -c 'binary=${'$'}1; message=${'$'}2; shift 2; $execPrefix "${'$'}binary" "${'$'}@" -- "${'$'}message"' yxi-launch "${'$'}bin" "${'$'}prompt" $quotedArguments 2>/dev/null || { echo '$tag:failed'; exit 0; }
else
  tmux new-session -d -s "${'$'}n" -c "${'$'}want" /bin/sh -c 'binary=${'$'}1; shift; $execPrefix "${'$'}binary" "${'$'}@"' yxi-launch "${'$'}bin" $quotedArguments 2>/dev/null || { echo '$tag:failed'; exit 0; }
fi
sleep 0.2
tmux has-session -t "=${'$'}n" 2>/dev/null || { echo '$tag:exited'; exit 0; }
got=${'$'}(tmux display-message -p -t "=${'$'}n:" '#{pane_current_path}' 2>/dev/null)
[ "${'$'}got" = "${'$'}want" ] || { echo '$tag:changed-directory'; exit 0; }
echo '$tag:ok'
""".trimIndent()
    }
    companion object {
        fun newRequestId() = UUID.randomUUID().toString().replace("-", "")
    }
}
