# Hub 订阅归档核验

2026-11-24。4 组共享资料逐字节一致（8 个副本），SHA-256 如下。

| Hub 文件 | Player 文件 | SHA-256 |
| --- | --- | --- |
| docs/requirements/技术方案.md | docs/requirements/z-jellyfin-hub技术方案.md | E7405FBECB96EA5957498A0A0C3E9461AB488D44ECCE180F196828E856AA8666 |
| docs/api/订阅协议.md | docs/api/订阅协议.md | 19B0E760FC8B8BB444D41DB335844A58CC78227D939C767E7E9080C71CA42872 |
| docs/api/subscription-v1.schema.json | docs/api/subscription-v1.schema.json | 2BEC8D649B4CDA78B38FA44F84F98E647F2839C7175FBD7869C83143C15D45C2 |
| docs/api/subscription-v1.example.json | docs/api/subscription-v1.example.json | 8931AE8CDA2AA7C62BA1CFD3D91E23E56BBB374D4BA6C9AAC6BA4CCA6C281AD5 |

本轮归档范围：Hub/Web 的 docs 下共 9 个文件（4 个共享资料、规范、部署、验收、两份执行计划）；Player 共享需求/协议/本核验共 6 个文件，Android 与 iOS 子项目各 1 个本地交接入口。各端正文、协议和验收的相对引用已检查。

- [共享接入验收](Hub订阅接入验收.md)
- [Android 交接](../../android/docs/requirements/Hub订阅接入.md)
- [iOS 交接](../../ios/docs/requirements/Hub订阅接入.md)
- [Hub 实施验收](../../../z-jellyfin-hub/docs/acceptance/2026-11-24-首版验收.md)
- [既有媒体交互原型](../prototype/index.html)

本轮未另行设计订阅/后台交互原型，管理后台验收针对实际界面；原媒体原型保留。Android 已实现和真机验证，iOS 仅交接，uni-app 无工程。生产 HTTPS 订阅联调及容器部署未执行。
