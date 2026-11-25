# iOS Hub 订阅交接

适用工程：ios；当前仅交接归档，未实现、构建或安装。

需求与联合验收见 [接入验收](../../../docs/requirements/Hub订阅接入验收.md)；[技术方案](../../../docs/requirements/z-jellyfin-hub技术方案.md)、[协议](../../../docs/api/订阅协议.md)、[Schema](../../../docs/api/subscription-v2.schema.json)、[样例](../../../docs/api/subscription-v2.example.json) 均为工程内本地引用。

后续按协议实现独立 HTTPS URLSession（不使用媒体代理、不跟随重定向）、Keychain 缓存、候选鉴权验证、播放中延迟替换连接、304/停用/离线恢复。媒体 HTTP 代理需要结合 AVPlayer 独立验证，不能直接照搬 Android OkHttp 实现；字幕/音轨偏好继续使用 Jellyfin。

验收除共享清单外，还需验证 iOS 前后台恢复、Keychain 丢失或重装、原生播放代理与字幕切换。iOS 尚未支持导入本轮订阅，不发布未经验证的安装包。

新增 v2 要求：令牌唯一定位订阅，不解析链接 ID；持久化 serverInstanceId 与内部 subscriptionId，按该组合防回退；新组合先重验登录再允许较低版本。已有可信缓存迁移独立设计，网络不接收 v1。

## 可选代理与直连

本轮需求见[直连模式](../../../docs/requirements/直连模式.md)，协议与样例见上述共享本地文档以及[直连样例](../../../docs/api/subscription-v2.direct.example.json)。connection.proxy 缺失或 null 时直连；对象存在必须完整有效，失败不自动切换模式。直连服务器必须可达，保留路径前缀，媒体和字幕不附带代理认证头。Hub 对此配置要求 Android 构建号至少 72，iOS 版本兼容门槛在实际接入前另行定义。
iOS 仅交接，尚未实现直连与可选代理，不宣称已构建验收。
