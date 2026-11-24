# Android Hub 订阅交接

适用工程：android；包名 cn.jiuwusan.jellyfin.player，0.6.42 / 56。

本轮已实现。共享需求、协议、验收与跨端范围以 [接入验收](../../../docs/requirements/Hub订阅接入验收.md) 为准；[技术方案](../../../docs/requirements/z-jellyfin-hub技术方案.md)、[协议](../../../docs/api/订阅协议.md)、[Schema](../../../docs/api/subscription-v1.schema.json)、[样例](../../../docs/api/subscription-v1.example.json) 均为工程内本地引用。

实现模块：ManagedConfiguration、SubscriptionClient、SubscriptionSettings、SubscriptionController、MainActivity。变更范围包括直连引导、缓存加密、验证与应用、设置页和播放边界；代理媒体链路、语言偏好和硬解策略保持。

受控集成测试与荣耀实际播放通过；Hub 生产 HTTPS 部署后仍需完成共享清单，不将模拟协议测试视为上线验收。
