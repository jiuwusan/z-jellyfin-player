# iOS Hub 订阅交接

适用工程：ios；当前仅交接归档，未实现、构建或安装。

需求与联合验收见 [接入验收](../../../docs/requirements/Hub订阅接入验收.md)；[技术方案](../../../docs/requirements/z-jellyfin-hub技术方案.md)、[协议](../../../docs/api/订阅协议.md)、[Schema](../../../docs/api/subscription-v2.schema.json)、[样例](../../../docs/api/subscription-v2.example.json) 均为工程内本地引用。

后续按协议实现独立 HTTPS URLSession（不使用媒体代理、不跟随重定向）、Keychain 缓存、候选鉴权验证、播放中延迟替换连接、304/停用/离线恢复。媒体 HTTP 代理需要结合 AVPlayer 独立验证，不能直接照搬 Android OkHttp 实现；字幕/音轨偏好继续使用 Jellyfin。

验收除共享清单外，还需验证 iOS 前后台恢复、Keychain 丢失或重装、原生播放代理与字幕切换。iOS 尚未支持导入本轮订阅，不发布未经验证的安装包。

新增 v2 要求：令牌唯一定位订阅，不解析链接 ID；持久化 serverInstanceId 与内部 subscriptionId，按该组合防回退；新组合先重验登录再允许较低版本。已有可信缓存迁移独立设计，网络不接收 v1。
