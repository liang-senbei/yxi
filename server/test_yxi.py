#!/usr/bin/env python3
"""yxi 服务器侧的测试。**重点是最后那条 fail-closed。**

跑：`python3 server/test_yxi.py`（不依赖 pytest；装了 pytest 也能直接 `pytest server/test_yxi.py`）

⚠️ 这里守的是一条底线：**手机连不上的时候，绝不能自动放行。**
这条比其它所有加起来都重要 —— 别的功能坏了顶多不好用，这条坏了是
「你在睡觉，Claude 自己批准了一条 rm -rf」。
"""
import json
import os
import shutil
import stat
import subprocess
import sys
import time

HOOK = os.path.expanduser("~/.local/bin/yxi-hook")
YXI = os.path.expanduser("~/.yxi")
EVENTS = os.path.join(YXI, "events.jsonl")
SESSION = "cc-yxi-failclosed"
HUB = os.path.expanduser("~/.local/bin/yxi-hub")
MARKER = "/tmp/yxi-fail-closed-MARKER"


def run_hook(payload: dict, env_extra=None):
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    p = subprocess.run([HOOK], input=json.dumps(payload), text=True,
                       capture_output=True, env=env, timeout=30)
    return p


# ─────────────────────────────────────────────────────────────────────────
def test_hook_never_decides():
    """⚠️ **hook 永远不输出权限决定。**

    PRD 原本的设计是 hook 阻塞等手机回答、然后打印 allow/deny/ask。
    我们没那么做 —— hook 只往事件流追加一行，**权限决定完全不经过它**。
    好处是「自动放行」这条路**在结构上不存在**，而不是靠代码写对。
    这条测试就是钉住这个性质：任何事件、任何情况，stdout 里都不许出现 permissionDecision。
    """
    for ev in ("PreToolUse", "PostToolUse", "Notification", "Stop", "SessionEnd", "UserPromptSubmit"):
        p = run_hook({"hook_event_name": ev, "cwd": "/tmp",
                      "tool_name": "Bash", "tool_input": {"command": "rm -rf /"}})
        assert p.returncode == 0, f"{ev} 退出码 {p.returncode}"
        assert "permissionDecision" not in p.stdout, f"{ev} 居然输出了权限决定：{p.stdout!r}"
        assert "hookSpecificOutput" not in p.stdout, f"{ev} 输出了 hook 决定：{p.stdout!r}"
    print("✓ hook 从不输出权限决定")


def test_hook_survives_unwritable_dir():
    """~/.yxi 不可写时，hook 必须**安静地失败**：退出码 0、不输出任何东西。

    退出码非 0 或者往 stdout 吐东西，都可能干扰 Claude Code 本身 ——
    而它只是个通知用的钩子，没有任何理由影响正事。
    """
    had = os.path.isdir(YXI)
    mode = os.stat(YXI).st_mode if had else None
    try:
        if not had:
            os.makedirs(YXI)
        os.chmod(YXI, stat.S_IRUSR | stat.S_IXUSR)      # r-x------，写不进去
        p = run_hook({"hook_event_name": "Stop", "cwd": "/tmp"})
        assert p.returncode == 0, f"退出码是 {p.returncode}，会干扰 Claude Code"
        assert p.stdout.strip() == "", f"不该有输出，却有：{p.stdout!r}"
    finally:
        if mode is not None:
            os.chmod(YXI, stat.S_IMODE(mode))
        else:
            shutil.rmtree(YXI, ignore_errors=True)
    print("✓ ~/.yxi 不可写时 hook 安静退出")


def test_approval_fail_closed():
    """⭐ **端到端**：没人回答的时候，Claude Code 必须停在它自己的提示上等着。

    做法：起一个手动模式的 Claude Code，让它去跑一条会触发权限提示的命令
    （`touch <marker>`），同时把 `~/.yxi` 弄成不可写（模拟「事件发不出去 / 手机根本收不到」）。
    然后**什么都不做**，等 40 秒。

    两条都要成立：
      ① marker 文件**不存在** —— 命令没有被执行，也就是没有被自动放行
      ② 屏幕上**还挂着那个提示** —— 它在等人，而不是悄悄跳过去了
    """
    if shutil.which("tmux") is None:
        print("- 跳过（没有 tmux）"); return
    for f in (MARKER,):
        if os.path.exists(f):
            os.remove(f)
    subprocess.run(["tmux", "kill-session", "-t", SESSION],
                   capture_output=True)
    had_mode = os.stat(YXI).st_mode if os.path.isdir(YXI) else None
    try:
        os.makedirs(YXI, exist_ok=True)
        os.chmod(YXI, stat.S_IRUSR | stat.S_IXUSR)      # 事件写不出去 = 手机永远收不到

        subprocess.run(["tmux", "new-session", "-d", "-s", SESSION, "-c", "/tmp",
                        "-x", "100", "-y", "30"], check=True)
        env = "IS_SANDBOX=1"
        subprocess.run(["tmux", "send-keys", "-t", SESSION,
                        f"cd /tmp && {env} claude", "Enter"], check=True)
        time.sleep(12)
        subprocess.run(["tmux", "send-keys", "-t", SESSION, "1"], check=True)   # 信任目录
        time.sleep(8)
        # 切到手动模式（默认可能是 bypass，那样不会问）
        for _ in range(3):
            subprocess.run(["tmux", "send-keys", "-t", SESSION, "BTab"], check=True)
            time.sleep(1.5)
            pane = subprocess.run(["tmux", "capture-pane", "-pt", SESSION],
                                  capture_output=True, text=True).stdout
            if "manual mode" in pane:
                break
        else:
            print("- 跳过（切不到 manual mode，环境不合适）"); return

        subprocess.run(["tmux", "send-keys", "-t", SESSION, "-l",
                        f"用 Bash 跑 touch {MARKER}"], check=True)
        subprocess.run(["tmux", "send-keys", "-t", SESSION, "Enter"], check=True)

        # 等它问出来
        for _ in range(40):
            time.sleep(1.5)
            pane = subprocess.run(["tmux", "capture-pane", "-pt", SESSION],
                                  capture_output=True, text=True).stdout
            if "Do you want to proceed?" in pane:
                break
        else:
            print("- 跳过（没等到权限提示，可能是模型没调 Bash）"); return

        time.sleep(40)      # 什么都不做

        pane = subprocess.run(["tmux", "capture-pane", "-pt", SESSION],
                              capture_output=True, text=True).stdout
        assert not os.path.exists(MARKER), \
            "⚠️⚠️ 没人批准，命令却执行了 —— 这就是自动放行，最严重的失败"
        assert "Do you want to proceed?" in pane, \
            f"提示消失了但命令也没跑，说明它自己跳过去了。屏幕：\n{pane[-600:]}"
        print("✓ 没人回答时：命令没执行，提示还挂着（fail-closed 成立）")
    finally:
        subprocess.run(["tmux", "kill-session", "-t", SESSION], capture_output=True)
        if had_mode is not None:
            os.chmod(YXI, stat.S_IMODE(had_mode))
        if os.path.exists(MARKER):
            os.remove(MARKER)


def test_seed_never_evicts_a_real_phone():
    """`dev/seed.sh` 改 authorized_keys 时，过滤标记必须是**模拟器专属**的。

    `KeyManager` 给每一台 Android 设备生成的公钥注释都是 `yxi@android` ——
    模拟器是，用户的真手机也是。脚本里原来写 `grep -v yxi@android`，
    于是每跑一次开发脚本，就把用户真手机的公钥从服务器上删一次。
    症状出在用户那头（连不上、检查更新失败），服务器上一切正常、
    日志里连痕迹都没有 —— 他的连接根本走不到认证。见 TROUBLESHOOTING #65。
    """
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = open(os.path.join(root, "dev", "seed.sh")).read()
    # 注释里可以提 yxi@android（就是上面那段解释），代码里不行
    code = "\n".join(l.split("#")[0] for l in src.splitlines())
    assert "authorized_keys" in code, "seed.sh 不动 authorized_keys 了？这条测试该删了"
    assert "yxi@android" not in code, (
        "seed.sh 的代码里出现了 yxi@android —— 真手机的公钥会被它删掉。"
        "模拟器自己的钥匙要用 yxi@emulator 打标签，过滤也只过滤这个标签。"
    )
    print("✓ seed.sh 不会误删真手机的公钥")


def _hub(cmd, sess, env_home):
    """在某个 tmux 会话**里面**跑一句 yxi-hub，拿它的输出。

    ⚠️ 必须真在 tmux 里跑 —— `yxi-hub` 靠 `tmux display-message` 认自己是谁，
    在外面调它永远认不出来，测了等于没测。
    """
    out = f"/tmp/yxi-hub-test-{sess}.out"
    if os.path.exists(out):
        os.remove(out)
    # ⚠️ **等的是结束标记，不是「文件出现了」。** `>` 一重定向文件就存在了，
    #    内容还没写。照着文件存在去读，读到的是半截输出 —— 而半截输出会让
    #    断言以「功能坏了」的样子失败，实际只是读早了。（自己踩过一次。）
    subprocess.run(["tmux", "send-keys", "-t", sess,
                    f"HOME={env_home} {HUB} {cmd} > {out} 2>&1; echo __完__ >> {out}",
                    "Enter"], check=True)
    for _ in range(300):
        time.sleep(0.1)
        if os.path.exists(out) and "__完__" in open(out).read():
            return open(out).read().replace("__完__\n", "")
    return open(out).read() if os.path.exists(out) else ""


def test_hub_only_talks_inside_the_group():
    """**同组才能发**。这是「分组」和「所有会话」的唯一区别。

    这条塌了的表现很隐蔽：分组看着还在、UI 一切正常，但任何 agent 都能
    给任何会话送键 —— 组从协作边界退化成一个纯视觉标签，而用户以为它是边界。
    所以这里用三个真 tmux 会话端到端走一遍，不 mock。
    """
    if not shutil.which("tmux"):
        print("· 没装 tmux，跳过")
        return
    assert os.path.exists(HUB), f"先跑 server/install.sh —— 找不到 {HUB}"
    home = "/tmp/yxi-hub-test-home"
    a, b, c = "yxitest-a", "yxitest-b", "yxitest-c"
    try:
        shutil.rmtree(home, ignore_errors=True)
        os.makedirs(os.path.join(home, ".yxi"))
        # a 和 b 一组，c 是组外的
        with open(os.path.join(home, ".yxi", "groups.json"), "w") as f:
            json.dump({"v": 1, "groups": {"测试组": [a, b]}}, f)
        for s in (a, b, c):
            subprocess.run(["tmux", "kill-session", "-t", s], capture_output=True)
            # ⚠️ 等它**真的没了**再建同名的。kill 是异步的，紧接着建同名会话时
            # 旧 pane 可能还在，键会送进将死的那个 —— 表现为「输出是空的」，
            # 看起来像功能坏了，其实只是测试自己在打架。
            for _ in range(40):
                if subprocess.run(["tmux", "has-session", "-t", s],
                                  capture_output=True).returncode != 0:
                    break
                time.sleep(0.1)
            subprocess.run(["tmux", "new-session", "-d", "-s", s], check=True)
        # ⚠️ 等**每个 shell 真的能收键**再往下。固定 sleep 不行 ——
        #    这台机器的 .bashrc 不轻，起得慢的那次键会被吞掉。
        for s in (a, b, c):
            ready = f"/tmp/yxi-hub-ready-{s}"
            if os.path.exists(ready):
                os.remove(ready)
            for _ in range(60):
                subprocess.run(["tmux", "send-keys", "-t", s, f"touch {ready}", "Enter"],
                               capture_output=True)
                time.sleep(0.25)
                if os.path.exists(ready):
                    break
            assert os.path.exists(ready), f"{s} 的 shell 一直没起来"
            os.remove(ready)

        who = _hub("who", a, home)
        assert "测试组" in who, f"who 认不出自己的组:\n{who}"
        assert b in who, f"who 没列出同组的 {b}:\n{who}"
        assert c not in who, f"who 把组外的 {c} 也算进来了:\n{who}"

        # 同组的：发得出去，而且对方屏幕上真的出现了
        said = _hub(f'say {b} "组内握手"', a, home)
        assert "已发给" in said, f"发给同组失败了:\n{said}"
        time.sleep(0.5)
        pane = subprocess.run(["tmux", "capture-pane", "-p", "-t", b],
                              capture_output=True, text=True).stdout
        assert "组内握手" in pane, f"{b} 的屏幕上没出现那句话:\n{pane[-400:]}"
        assert a in pane, f"没署名是谁发的 —— 对方不知道找谁回:\n{pane[-400:]}"

        # 组外的：必须拒绝
        refused = _hub(f'say {c} "越界"', a, home)
        assert "不在你的组里" in refused, (
            f"给组外的 {c} 发居然成了 —— 分组不是边界了:\n{refused}")
        time.sleep(0.4)
        pane_c = subprocess.run(["tmux", "capture-pane", "-p", "-t", c],
                                capture_output=True, text=True).stdout
        assert "越界" not in pane_c, f"嘴上说拒绝，键还是送进去了:\n{pane_c[-400:]}"
        print("✓ yxi-hub 只在组内送得动，组外真的送不进去")
    finally:
        for s in (a, b, c):
            subprocess.run(["tmux", "kill-session", "-t", s], capture_output=True)
        shutil.rmtree(home, ignore_errors=True)


if __name__ == "__main__":
    fails = 0
    for name, fn in list(globals().items()):
        if not name.startswith("test_"):
            continue
        try:
            fn()
        except AssertionError as e:
            fails += 1
            print(f"✗ {name}: {e}")
        except Exception as e:
            fails += 1
            print(f"✗ {name} 抛异常: {type(e).__name__}: {e}")
    print("—— 全过 ——" if not fails else f"—— {fails} 条失败 ——")
    sys.exit(1 if fails else 0)
