# 1.4.15 上线与回退复核

只读审查 `publish.sh` / `validate_release.py`；未查询 CI、下载或部署。指定候选：run 35974085873，冻结 SHA e744ba255fb0bbfadd38cb973efd91f0fdfbcf27；线上 1.4.14、11GiB 空闲为主代理提供的状态，执行前重新确认。

## 现有脚本不足

- `publish.sh` 已校验同一 Windows job 两项成功和冻结 SHA，但直接 rsync 整个 Releases 目录，发布顺序未约束，没有可回退快照；不能当作原子上线流程。
- 验证器已校验严格递增版本、nupkg大小/SHA256/非符号链接；Setup仅检查存在非空，未核验候选版本、来源哈希；RELEASES没有解析检查。
- 末尾 curl -sI 不检查HTTP状态且只看头，不能证明公网内容与候选一致。

## 发布前必须持有的证据

1. CI completed/success、headSHA准确，Windows同一job工作台回归与安装启动成功；下载的 Yxi-windows 确属该run。保存run元数据及artifact ID；workflow显示名可重名，应核对实际workflow路径 `.github/workflows/desktop.yml`。
2. 安装启动日志确认1.4.15；本地验证器通过；记录Setup、nupkg、JSON feed、RELEASES的SHA256。RELEASES若引用包，逐个确认文件已存在且内容匹配，禁止缺包feed。
3. 重新读取线上feed为1.4.14；目的地无已存在不同内容的1.4.15包；磁盘足够容纳旧mutable备份+candidate staging；无并发发布。

## 最短上线顺序

1. 在hk13同一文件系统建立独立staging和带run/SHA标识的备份目录（放web根外）；获取发布锁。
2. 备份当前 `Yxi-win-Setup.exe`、`releases.win.json`、`RELEASES`（不存在则记不存在），记录哈希。所有旧版本nupkg原地保留。
3. 上传候选到staging，服务器端重算所有哈希/大小，与本地证据一致；发布锁内再检查线上版本未变化。
4. **先发布不可变 `Yxi-1.4.15-full.nupkg`**：同文件系统rename或无覆盖link；若同名已存在，只有哈希一致才可复用，绝不覆盖不同内容。通过公网实际GET验证包哈希/长度。
5. 以同目录临时文件+rename原子替换Setup；核对公网GET哈希。此时旧feed仍可安全使用旧包。
6. RELEASES（旧客户端feed）在全部引用包已可取后原子替换；**最后原子替换 releases.win.json**。两种feed不是跨文件事务，但每份引用都始终有效。权限文件644、目录可读。
7. 公网GET核对feed完整内容、1.4.15包引用与哈希，再保存上线证据。勿用HTTP200或HEAD代替内容验证。保留备份和候选，不删除旧版本。

## 回退

- Feed发布前失败：保持旧feed；Setup已换则原子恢复旧Setup；保留新增nupkg，不清旧包。
- Feed发布后失败：锁内先原子恢复旧JSON及RELEASES，停止继续推荐1.4.15；再恢复旧Setup，公网GET确认与备份哈希一致。恢复不存在的旧文件时按备份记录处理，勿误删历史包。
- 恢复feed不等于自动降级已安装1.4.15的用户；实际需要纠正时发更高修复版本，不重写1.4.15包或伪造版本。
