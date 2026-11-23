# 重叠SRT拖动恢复 Implementation Plan

用户已确认修复方案。当前master实施，不创建分支，验收后按用户指示提交并推送。使用测试先行及逐项执行，结束时做一次只读复核。

**Goal:** 内嵌SRT通过Jellyfin独立提取，完整SRT整理为非重叠的有效字幕区间，拖动后显示所有尚未结束的字幕。

**Architecture:** 仅撤除srt/subrip的Embed能力声明，保留External与既有烧录恢复。新增SRT解析工厂包装Media3 SubripParser：收集完整文件cue，按起止事件生成互不重叠的显示快照，遵循OutputOptions。默认位置的同时有效字幕合为多行以避免互相遮盖，保留文本span；有定位的cue保持定位。缓存仍存原始文件、显示样式沿用透明背景。设置数量与文本预算，异常由既有加载失败转烧录流程接管。

**Scope:** 不修改服务配置、媒体文件、代理、自动登录、音轨或画质；ASS/TTML/图片策略不变。不承诺修复Media3原生Embed底层逻辑，保留其诊断用例。

- [x] 先加区间快照检查（100–330/150–550/200–450ms、边界、乱序、相同文本、样式、起播筛选及资源限制），观察RED。
- [x] 实现有界SRT区间整理与SubtitleParser.Factory，仅SRT应用；修改能力声明并接入现有播放器媒体源。
- [x] 在原MKV重叠样本上让fixture遵循真实能力协商并返回提取SRT，检查前后拖动、初始续播、字幕开关、暂停和实际可见多行；保留原生Embed诊断。
- [x] 真实Jellyfin内部SRT提取与客户端文字播放、缓存、故障恢复、PGS和音轨/画质回归；只读复核及修正。
- [x] 0.6.39安装荣耀，APK复制下载目录并核对哈希；清理测试包/设备样本，记录验收及限制。

**Files:** 新增SrtSubtitleParserFactory.java、SrtCueTimeline.java；修改JellyfinApi.profile、MainActivity媒体源工厂；复用SubtitleCompatibilityCheck并增加真实协商/区间检查。旧明确Embed fixture作为底层诊断保留，新的应用路径必须真实请求External，不能仅手动篡改选择为External掩盖协商问题。

## 实施记录

- RED：新区间类不存在时失败；GREEN：边界、乱序、重复文字、样式、定位、资源预算及OutputOptions通过。
- 受控播放发现末区间截短，定位ProgressiveMediaPeriod按最后样本开始时间加10ms估算独立源时长；补末尾空cue后，普通SRT末区间及重叠SRT的500ms末段均通过。没有修改Media3依赖源码。
- 原样本250ms初始续播、前后拖动、OFF/ON缓存恢复均通过。截图实际显示全部3条字幕；同一轨仅一次下载。
- 真实服务确认physical internal=true、DeliveryMethod External；三点拖动、缓存及120秒72次cue更新零不匹配。
- 一次只读复核未发现阻断问题；核对本机Media3字节码，OutputOptions顺序一致、预算异常会包装为ParserException并进入既有烧录恢复。
- 最终：原8组故障、透明字幕样式、PGS/文本与音轨画质回归、投递协议均通过。新增srt-budget真实注入129条同时重叠，验证只有1轮同轨烧录、坏缓存移除和位置/画质保留。
- 0.6.39主包/测试包构建成功，荣耀安装版本53。下载目录副本与构建APK哈希一致；设备测试包、样本和截图清理，主包与配置保留。验收后用户指示提交并推送。
