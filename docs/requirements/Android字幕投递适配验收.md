# Android 字幕投递适配

2026-11-23；Android 0.6.35（49），荣耀 BVL-AN00。当前 master 分支实施，Android 已适配，iOS 未实施。保留代理、自动登录、账号语言偏好、画质和既有播放功能；不修改服务配置或账号偏好。

## 本轮行为

- 显式 DeliveryMethod 优先：Embed 从内嵌轨映射，External 独立加载，Encode 强制视频烧录。ASS 等文本被指定 Encode 时也正确烧录。
- 缺失 DeliveryMethod 才采用原文本/图片分类兼容。未知方法不猜测；不支持的格式、缺少或跨域 External 地址显示暂不可用，手动点击提示且保留原选择。
- External 使用经同源校验的 DeliveryUrl，不因扩展名与原编码不同而自行重建。已知返回扩展名用于选择实际 MIME。仅旧响应既无投递方法也无返回地址时构造兼容提取接口。
- 标准 Jellyfin 完整字幕提取地址统一 startPositionTicks=0、endPositionTicks=0、copyTimestamps=true、addVttTimeMap=false，避免截断、相对时间轴和 HLS 时间映射影响独立字幕。其他参数保留，URL 中鉴权令牌移除并沿用 Authorization 请求头。
- 初次与二次 HLS 协商均读取字幕投递信息，以最终结果配置 HLS 参数和字幕轨。涉及 Encode 的选择沿用重取流及失败回滚；内嵌和独立字幕尽量本地切换。
- 无默认字幕或手动关闭时，直连与 HLS 一致禁用文字轨。不可用默认轨不会让 Media3 随意自动选中另一条。
- 设备能力增加可内嵌的 SRT/TTML 文本声明，独立文本包含 TTML；图片仍声明 Encode，没有开放本地 PGS/DVB 渲染。

## 验证

| 检查 | 结果 |
| --- | --- |
| 旧实现复现 | 新受控检查以 Encode text subtitle did not force burn-in 失败 |
| subtitle-delivery | ALL_PASS：文本 Encode、返回 URL、实际 MIME、完整时间轴、头部鉴权、Embed 索引映射、直连自动关闭、手动关闭、跨域和未知方法 |
| 二次协商 | Embed→External 最终不烧录并加载 VTT；Embed→Encode 最终烧录且禁止视频直拷，均通过 |
| 原 subtitles | ALL_PASS：旧响应兼容、默认 PGS、关闭、文本、音轨与画质预算、禁止双字幕 |
| languages | ALL_PASS：服务端默认选轨、字幕关闭模式、手动覆盖、账号偏好读写保护及设置页读取 |
| subtitles-real | ALL_PASS：《人类清除计划2》默认中文 PGS，切英文、关闭、恢复自动均保持600000ms，暂停与音轨保持；《魅影神捕》实际文本cue通过 |
| 解码和配置 | 硬解 c2.qti.avc.decoder，H.264，原1080p/4Mbps请求上限保留；加密连接数据逐字节不变 |
| 构建和复核 | 主包、测试包构建成功；diff格式检查通过；独立只读代码复核未发现阻断问题 |

本地证据位于被忽略的 verification/subtitle-delivery。交付前卸载专用测试包，清理设备截图及独立测试偏好文件，保留主应用和配置。APK复制到系统下载目录，未自动提交或推送。

## 验证范围与后续项

Embed 映射按官方的全部非外挂流序号对应 TEXT group，当前由受控轨道验证；实际直连 Embed 片源尚未完整验收。真实字幕测试使用现有 HLS 转码链路。

本轮完成第一步投递、关闭和切换对齐。HTTP瞬时失败的字幕专属重试、解析失败自动烧录兜底、图片字幕本地显示仍待独立实现与验收；不因一段时间没有cue判断字幕失败。独立 HLS 字幕播放列表尚未支持，不将m3u8当作文本文件解析。没有声明全量Lint通过，既有DisplayCutout NewApi限制未修改。
