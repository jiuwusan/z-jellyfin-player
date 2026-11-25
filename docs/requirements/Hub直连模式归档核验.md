# Hub 直连模式归档核验

Hub/Web 为本轮实现端；原生 Android 为本轮实现及真机联调端；iOS 仅更新交接和共享协议，不宣称已实现，uni-app 无对应工程。

共核验 5 组共享文件，复制后 SHA-256 一致。Android/iOS 各有一份本地交接入口；正文、协议、样例、原型和验收引用指向本工程已有文件。

| Hub 来源 | Player 归档路径 | SHA-256 |
| --- | --- | --- |
| docs/api/订阅协议.md | docs/api/订阅协议.md | CC35093BA6E69D816CF733540E5AF094361C846BDFCB97E5A42923DA3AD6035A |
| docs/api/subscription-v2.schema.json | docs/api/subscription-v2.schema.json | BAAEE06B563A79F4C8F7709C1BE54C6D3B836D21F5FC28B1BC7CB17017B29D3C |
| docs/api/subscription-v2.example.json | docs/api/subscription-v2.example.json | 82F028CD5E478683E1B38B9712A9C9BC55A395CF9DC9E05607439B630CBF7E7D |
| docs/api/subscription-v2.direct.example.json | docs/api/subscription-v2.direct.example.json | 50B0D722225C572D4EC9A6C93B8998A2A57204E54F26FF09E4125BF6616D901A |
| docs/requirements/直连模式.md | docs/requirements/直连模式.md | 5527083A089EAD4D1071ECA102ACD15954968B3D44B50BA89B8AC6BDFA94FE08 |

- [正文](直连模式.md)
- [协议](../api/订阅协议.md)
- [直连样例](../api/subscription-v2.direct.example.json)
- [Android 交接](../../android/docs/requirements/Hub订阅接入.md)
- [iOS 交接](../../ios/docs/requirements/Hub订阅接入.md)
- [验收](Hub订阅接入验收.md)
- [原型](../prototype/index.html)
