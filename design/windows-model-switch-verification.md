# 模型切换与强度控件：验证记录

状态：开发线未发布；以下证据不等同完整Windows交互验收。

## 已取得证据

- 配置映射：ConfiguredModelsTest七项通过，覆盖别名映射、去重、循环、明确1M后缀和未知别名隐藏；来源为服务器/项目配置，不代表已覆盖CLI启动环境优先级。
- 控制器：既有ModelSwitchControllerTest十三项通过。
- 真实CLI：隔离HOME、独立tmux socket、仅本地stub假密钥，Claude Code 2.1.267直接切第三方模型ID，观测请求model=deepseek-stub-v3、output_config.effort=xhigh。未调用真实供应商。
- 真实转录回放：a6d1651上的ModelSwitchRealTranscriptTest四项经过产品Entry/parser/controller/store，验证两条命令回执后、下一条assistant前可更新状态；缺回执不假报Applied；门禁拒绝不发键。发送端与门函数仍是测试替身。
- 编译覆盖：a6d1651覆盖统一输入框0aaf4a4和统一侧栏搜索72fe716。
- 控件：5e3fcfe的实际EffortControl经ImageComposeScene渲染三态，PNG路径为hk13:/tmp/mdprobe/effort2/effort-{low,high,max}.png；root取回low/max并视觉检查。轨道30px、白滑块、蓝/紫色与渐变正常。不是按常量另画的替代图。

## 本轮已修问题

1. 转录未解析Set effort level回执，导致状态滞留；现已接入。
2. 模型回执被截成40字符；现保留完整第三方ID。
3. 缺model/effort字段被当成拒绝；现继续等待证据。
4. 切换后同一调度轮立即续发可能撞上CLI重绘；runner已改为状态revision变化后下一轮重新探测，尚未执行验证该runner分支。
5. 低档颜色被白滑块遮住；调整轨道比例并重渲确认可见。

## 尚需完成

- Windows实际菜单、拖动/键盘、输入法、待发队列接续的交互检查。
- 中文字体排版：hk13缺CJK字体，截图有缺字，不能声称像素级完成。
- 实际供应商鉴权/协议差异以及运行器启动参数覆盖。
- 新会话、切换页面后缓存更新、同模型重选等场景按需要定向覆盖。

精确历史回退是独立需求，不在这些模型切换测试的证明范围内。
