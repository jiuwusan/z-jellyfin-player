# Android Hub 订阅交接

适用工程：android；包名 cn.jiuwusan.jellyfin.player，0.6.43 / 57。

本轮已实现。共享需求、协议、验收与跨端范围以 [接入验收](../../../docs/requirements/Hub订阅接入验收.md) 为准；[技术方案](../../../docs/requirements/z-jellyfin-hub技术方案.md)、[协议](../../../docs/api/订阅协议.md)、[Schema](../../../docs/api/subscription-v2.schema.json)、[样例](../../../docs/api/subscription-v2.example.json) 均为工程内本地引用。

实现模块：ManagedConfiguration、SubscriptionClient、SubscriptionSettings、SubscriptionController、MainActivity。变更范围包括直连引导、缓存加密、验证与应用、设置页和播放边界；代理媒体链路、语言偏好和硬解策略保持。

0.6.43 受控订阅/缓存迁移/服务重建恢复及播放会话边界测试通过，真实硬解为上一版历史记录；Hub 生产 HTTPS 部署后仍需完成共享清单，不将模拟协议测试视为上线验收。
