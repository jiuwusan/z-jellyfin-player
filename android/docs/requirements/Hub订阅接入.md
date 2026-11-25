# Android Hub 订阅交接

适用工程：android；包名 cn.jiuwusan.jellyfin.player，0.6.43 / 57。

本轮已实现。共享需求、协议、验收与跨端范围以 [接入验收](../../../docs/requirements/Hub订阅接入验收.md) 为准；[技术方案](../../../docs/requirements/z-jellyfin-hub技术方案.md)、[协议](../../../docs/api/订阅协议.md)、[Schema](../../../docs/api/subscription-v2.schema.json)、[样例](../../../docs/api/subscription-v2.example.json) 均为工程内本地引用。

实现模块：ManagedConfiguration、SubscriptionClient、SubscriptionSettings、SubscriptionController、MainActivity。变更范围包括直连引导、缓存加密、验证与应用、设置页和播放边界；代理媒体链路、语言偏好和硬解策略保持。

0.6.43 受控订阅/缓存迁移/服务重建恢复及播放会话边界测试通过，真实硬解为上一版历史记录；Hub 生产 HTTPS 部署后仍需完成共享清单，不将模拟协议测试视为上线验收。

## 可选代理与直连

本轮需求见[直连模式](../../../docs/requirements/直连模式.md)，协议与样例见上述共享本地文档以及[直连样例](../../../docs/api/subscription-v2.direct.example.json)。connection.proxy 缺失或 null 时直连；对象存在必须完整有效，失败不自动切换模式。直连服务器必须可达，保留路径前缀，媒体和字幕不附带代理认证头。Hub 对此配置要求 Android 构建号至少 72，iOS 版本兼容门槛在实际接入前另行定义。
Android 已实现，覆盖直连/代理切换、成功缓存和播放中的延迟应用；真实验收见本轮记录。
