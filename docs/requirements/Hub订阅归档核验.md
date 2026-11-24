# Hub 订阅归档核验

2026-11-24。6 组共享资料逐字节一致（12 个副本）。当前协议为 v2；v1 JSON 仅作历史缓存格式归档。

| Hub 文件 | Player 文件 | SHA-256 |
| --- | --- | --- |
| docs/requirements/技术方案.md | docs/requirements/z-jellyfin-hub技术方案.md | 54AE1BF6E5AD3648311CE3C9FCD603BCB6F7BAE712661483C6B5868FB48ADFCC |
| docs/api/订阅协议.md | docs/api/订阅协议.md | 3ABB36FD498FD3D61F968A15DA84651B20154364B548A4517FB81CB086F9C580 |
| docs/api/subscription-v1.schema.json | docs/api/subscription-v1.schema.json | 2BEC8D649B4CDA78B38FA44F84F98E647F2839C7175FBD7869C83143C15D45C2 |
| docs/api/subscription-v1.example.json | docs/api/subscription-v1.example.json | 8931AE8CDA2AA7C62BA1CFD3D91E23E56BBB374D4BA6C9AAC6BA4CCA6C281AD5 |
| docs/api/subscription-v2.schema.json | docs/api/subscription-v2.schema.json | 29130F65BE81443089EBA10375F630986A66D4A2FF9664D327EBEB50E6B37285 |
| docs/api/subscription-v2.example.json | docs/api/subscription-v2.example.json | 82F028CD5E478683E1B38B9712A9C9BC55A395CF9DC9E05607439B630CBF7E7D |

归档范围：Hub/Web docs 共 13 个文件；另有 deploy/nginx-subpath.conf.example。Player 共享需求/协议/本核验共 8 个文件，Android/iOS 子项目各 1 个本地交接入口。两端规范、正文、Schema/样例与验收引用已核对。

- [共享接入验收](Hub订阅接入验收.md)
- [Android 交接](../../android/docs/requirements/Hub订阅接入.md)
- [iOS 交接](../../ios/docs/requirements/Hub订阅接入.md)
- [Hub 实施验收](../../../z-jellyfin-hub/docs/acceptance/2026-11-24-首版验收.md)
- [Nginx 反代示例](../../../z-jellyfin-hub/deploy/nginx-subpath.conf.example)
- [既有媒体交互原型](../prototype/index.html)

本轮 hash 路由只修改 Hub/Web 和共享文档，Android/iOS 工程未改。此前 Android 0.6.43 已在荣耀验证协议/恢复/会话边界，iOS 仅交接、uni-app 无工程；生产 HTTPS 媒体联调及 Docker/NAS 部署未执行。
