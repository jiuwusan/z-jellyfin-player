# z-jellyfin

面向家庭使用的原生 Jellyfin 播放客户端。Android 和 iOS 工程在同一个仓库中独立构建。

| 目录 | 当前状态 |
| --- | --- |
| [android](android/) | 从 Android 0.2.0 验证版迁入。包含 HTTP 代理、自动登录、媒体库、详情、续播和 1080p／4 Mbps 转码策略。 |
| [ios](ios/) | SwiftUI 工程基线，包含启动页和服务器地址设置。Jellyfin 登录、取流及播放仍待接入。 |
| [docs](docs/) | 需求、交互原型和开发规范。 |

## 开始开发

- **Android**：用 Android Studio 打开 `android/`，或在该目录使用 JDK 21 和 Android SDK Platform 36 运行 `sh ./gradlew :app:assembleDebug`。Windows 使用 `gradlew.bat :app:assembleDebug`。
- **iOS**：用 Xcode 打开 `ios/JellyfinPlayer.xcodeproj`，选择共享 Scheme `JellyfinPlayer` 和 iOS 16 或更新的模拟器。真机需要在本地设置签名团队。

开发前阅读[项目开发规范](docs/standards/开发规范.md)。界面工作参照[媒体库功能需求](docs/requirements/媒体库功能需求.md)和[可点击原型](docs/prototype/index.html)。
