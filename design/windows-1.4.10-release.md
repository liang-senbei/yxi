# Windows 1.4.10 数据持久化修复

已发布官网，公开feed返回1.4.10，Setup链接HTTP200。未替用户在本机安装。

- 冻结源码：4bf9712e1ca6c618b8cdaa5f0593cbba0af5d691；隔离分支codex/windows-release-1.4.10。
- Windows CI35620405512成功，产物10649965380，903970574字节传输完成，下载PID1725557已结束。
- 更新包321592119字节，SHA256 B6DE22D6725D1A47EC04498FAFE85E8409F1B7D2B2D5C10A914C143EAC82C295。
- Setup SHA256 add84b845d7634ee289e59db8b3ce77b14260f09f8c3625404cde4cc4080fe86。
- 回滚备份：/var/www/yxi/desktop-rollback-1.4.9-before-1.4.10；更新清单最后替换，旧包保留。

范围：Windows数据从安装根迁到USERPROFILE/.yxi；迁移原文件保留、不覆盖现有目标、事务失败恢复；Yxi自生成SSH密钥路径经内容比对后更新；设置显示实际数据目录并支持打开/复制。DPAPI保护目的标识不变。

验证：最终候选上的UserDataMigrationTest九项通过，Windows CI构建/启动检查成功。真实跨安装用户数据体验仍需人工观察；未用生产凭据做模拟测试。当前用户的protected hosts/auth、known_hosts、prefs已预先备份至C:/Users/dfhzw/.yxi/backups/before-storage-fix-20260921-231404。

首次升级保留旧版数据副本，不先卸载旧版。已经删除且无备份的记录无法凭迁移重建，旧刷新令牌仍可能因服务端过期而需要登录。

不包含：1.4.9冻结之后的配置首页重设计、头像菜单新调整、中文裸链接修复、模拟器运行入口、精确历史回退。这些保留在开发线继续完成。
