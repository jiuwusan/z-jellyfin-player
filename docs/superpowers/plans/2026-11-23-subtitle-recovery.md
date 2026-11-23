# 字幕加载失败恢复 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. 用户要求在当前分支执行，不创建分支或 worktree；仅已完成的0.6.35按本次指令提交，后续改动不自动提交或推送。

**Goal:** 独立字幕失败时有限重试、更新地址、一次烧录兜底，最终可关闭字幕继续视频，并能手动重试。

**Architecture:** 以当前选中的独立字幕URL识别加载错误，非字幕错误沿用原处理。纯策略类限制恢复次数；播放器媒体源仅附加当前外部字幕，恢复复用现有位置/暂停/音轨/画质切换，失败最终移除字幕。没有cue不触发恢复。

**Tech Stack:** Java、Media3 1.8.0、OkHttp、现有原生Android与instrumentation。

**Spec:** docs/requirements/Android字幕投递适配验收.md 的后续项及本轮用户“继续后续”授权。

## Constraints

- 保留HTTP代理、授权和服务端默认选轨，画质默认1080p/4Mbps；不修改真实服务或账号配置。
- 外部字幕HTTP临时错误仅自动重试一次；鉴权错误不重复请求或烧录。404/410先重新协商一次，解析错误直接一次烧录，最终关闭字幕。不开放图片本地渲染。
- 回调按播放器、流和页面身份过滤；退出、切集、改选择后旧错误不能接管当前播放。后台不自动恢复播放。
- 每条字幕最多一次重新取址、一次烧录；手动重试可重置。视频源错误不当作字幕错误。

## Tasks

- [x] SubtitleRecoveryCheck先验证状态机、URL范围、HTTP重试预算、仅当前独立字幕配置与强制烧录请求，观察RED。
- [x] 新增SubtitleRecovery/SubtitleLoadPolicy，JellyfinApi支持明确烧录指定轨，PlaybackTracks按选择附加字幕。
- [x] MainActivity接入错误恢复、有限状态、字幕菜单重试、关闭后继续视频及退出/后台保护；相同流重建不重复上报会话。
- [x] 荣耀客户端HTTP故障注入验证网络重试、过期URL、解析失败、烧录失败关闭、暂停和位置及过期回调。
- [x] 真实默认PGS、文本cue、强制文本烧录回归；构建安装新版本并复制下载目录，清理测试产物，记录范围和限制。

## Review focus

- 非选中/非字幕错误不可烧录错误轨；只注册当前独立字幕避免其他文件阻断视频。
- 无对白不可当作错误；仅真实加载/解析错误触发。
- 恢复中再次失败不可无限重建；401/403/407和被取消请求不可反复尝试。
- 用户暂停、后台、退出和切集必须胜过异步恢复；无字幕的视频错误仍可正常展示失败。
- 会话上报和成功标识与首帧一致，不能恢复失败却显示字幕成功。

## 实施记录

- Ruling: 使用现有真实视频加客户端HTTP拦截替代合成视频服务。测试错误只在instrumentation进程内注入，不修改服务；代价是依赖家庭媒体和网络，可重复的协议检查仍使用loopback fixture。
- RED：新增检查在未实现SubtitleRecovery时失败；瞬时503真机检查复现请求重试后180000ms变为0、无法收到目标cue。无故障对照保留180000ms。
- 原因核对：Media3 1.8.0 ProgressiveMediaPeriod.configureRetry在首次取得字幕长度/seekMap之前把lastSeekPositionUs设为0。新增SubtitleDataSource对当前独立文本先完整下载、再交给解析器，最多重试一次且每次丢弃残缺内容；视频透传。16MiB上限防止异常文件耗尽内存，超过上限进入已有烧录恢复。修复后healthy/transient/stale/parser/burn-failed/auth真机均通过，180000ms位置保持。
- 首次只读复核的两项问题已处理：重新协商的401/403/407保留HTTP状态、不烧录；字幕地址刷新后的视频错误进入播放失败、不误烧录。补充对应故障用例。
- 新数据源只读复核未发现阻断问题；补充部分正文后断流的检查，确认重试从0开始、最终正文没有重复前缀。
- 最终：subtitle-recovery、8组真实恢复场景及手动重试、subtitle-delivery/subtitles/languages/subtitles-real均ALL_PASS。0.6.36(50)安装荣耀并确认重开进入首页；下载目录APK哈希与构建产物一致。专用测试包、设备测试截图和language-preference-check偏好文件已清理，主包及配置保留。本阶段按用户指示提交，不推送。
