# Android 字幕缓存与兼容性补验

2026-11-23，Android 0.6.37（51），荣耀 BVL-AN00。用户授权待优化清单第1项兼容性补验及第2项缓存，不新增字幕调节入口，不修改真实Jellyfin服务、代理或账号偏好。iOS未实施，本轮按用户指示提交，不推送。

## 缓存行为

- 每个JellyfinApi连接实例拥有独立内存缓存，完整字幕URI精确匹配；不缓存视频、图片字幕或烧录画面，不写磁盘，不增加配置入口。
- 最多24MiB、8条，10分钟绝对有效期；命中不延长有效期，按LRU淘汰。独立文件下载仍有16MiB保护。
- 仅完整下载成功后缓存；网络失败、残缺正文及Cache-Control:no-store不缓存。缓存命中后每次创建独立ByteArrayDataSource，拖动和重开不共享读取偏移。
- 解析失败清除对应文件，手动重试清除已有文件；重新登录清空。代际标记阻止失效或清空前的下载回填，连接实例之间不共享缓存。
- 保留HTTP代理、授权请求头、服务端默认选轨、画质、暂停与字幕失败恢复。内存缓存随进程退出丢失，服务端换地址后按新URI重新下载。

## 验证及证据

本地结果在被忽略的 `verification/subtitle-cache/`。

| 项目 | 结果/范围 |
| --- | --- |
| 缓存RED→GREEN | 未实现SubtitleCache时ClassNotFound失败；实现后TTL、LRU、总字节限制、超大文件、失效/清空代际、连接隔离通过 |
| 数据源 | 同一文件二次读取不发HTTP，独立读取偏移正确；no-store不缓存，失败最多重试一次且无缓存 |
| 缓存失效 | 重新登录清空通过；真实TTML解析失败后缓存为空；手动重试前注入坏缓存，仍能重新下载并显示cue |
| 真实SRT同步 | 《魅影神捕》第2集，暂停拖动到185140、777900、149059ms，显示正文逐次匹配字幕绝对时间轴 |
| 真实缓存复用 | 开启、关闭再开启目标SRT，目标URI总计1次HTTP下载；初次默认轨是另一条，按URI分别计数，不把默认轨误算成重复请求 |
| 持续播放 | 120秒内72次字幕更新，允许200ms回调采样偏差，零正文/时间轴不匹配，未重复下载、未重建播放器；不是整片数小时耐久验收 |
| 直连Embed | 官方物理内嵌SRT和ASS的MKV经生产播放器直连读取，选轨、逐位置正文、暂停与前后拖动通过。API响应/进度上报在测试进程拦截，不写入真实媒体记录 |
| ASS基础能力 | 中文、换行、固定位置、粗斜体、重叠cue和绝对时间解析通过；不是libass完整视觉等价验收 |
| 原有字幕回归 | subtitle-recovery-real、subtitle-cache、subtitle-delivery、subtitles-real均通过；默认中文PGS、切英文、关闭、恢复自动均保持600000ms与暂停状态，原音轨/画质保留 |

## 已确认限制，不计入通过

- **重叠内嵌SRT拖动失败**：`subtitle-compat`严格检查在官方sample_with_overlapping_srt.mkv的250ms失败。此时100–330ms、150–550ms、200–450ms三条均应有效，实际只返回最近开始的一条。故障日志保留；不能用“曾出现字幕”替代逐位置核对。需要进一步追踪Media3拖动时的样本保留/丢弃行为，本轮未修改该行为，修复前按项目规则说明原因和方案、取得用户确认。
- `subtitle-compat-supported`只覆盖普通内嵌SRT/ASS，不包含上述重叠样本；其通过不代表完整兼容性套件通过。
- 复杂ASS的移动、卡拉OK、动画等特效不承诺完整还原；当前文本仍由Media3绘制，服务端明确Encode时才烧录。补验另观察到无章节分隔空行的ASS可能丢失样式/定位，未扩展解析器行为。
- 其他设备、整片耐久、全量ASS特效和家庭媒体库所有内嵌轨均未穷举。

## 复现

运行 `android/verification-src/prepare-subtitle-compat.ps1 -Serial <荣耀序列号> -Adb <adb绝对路径>` 下载并校验3份小型MKV，二进制仅位于忽略目录和设备测试目录。来源为[AndroidX Media 1.8.0官方测试样本](https://github.com/androidx/media/tree/1.8.0/libraries/test_data/src/test/assets/media/mkv)，不将媒体文件加入仓库。

构建主包与androidTest包，覆盖安装后使用项目通用instrumentation入口，suite分别为`subtitle-cache`、`subtitle-cache-real`、`subtitle-compat-supported`；`subtitle-compat`保留上述已知失败，供后续修复对照。真实测试会按正常播放链路更新观看进度。

## 交付

主包和测试包构建成功，荣耀保留0.6.37主包；APK复制到下载目录 `z-jellyfin-0.6.37-debug.apk`，副本与构建产物SHA256一致。设备专用测试包、测试MKV与截图清理，主应用及代理/授权保留。没有新增NAS容器或端口映射；本轮按用户指示提交，不推送。完整兼容性严格检查仍包含上述已知失败，不能声称全量测试通过。
