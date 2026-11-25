# App 字体不跟随系统设置

目标：Android 标准／长辈模式自行决定字号，不受系统 fontScale 影响。当前 setTextSize 默认 sp，字幕和骨架亦有 sp 换算，因此通过 Activity 的局部资源上下文统一处理。

- [x] 新增受控检查：在隔离 Context 中模拟 0.85／1／1.3／1.6／2 倍系统字体，验证多个字号、输入框、下拉选项和对话框。
- [x] AppDisplayContext 仅生成 fontScale=1 的覆盖配置，其他 Configuration 字段留空；MainActivity 在 attachBaseContext 中调用 Activity 原生 applyOverrideConfiguration。不改系统设置或全局 Resources，不固定 density、方向、语言或屏幕尺寸。
- [x] Android 0.6.62（76）构建安装荣耀，核验标准／长辈模式及横屏播放器控件；不改订阅、代理、画质或服务配置。
- [x] 清理测试包，记录模拟测试与真机界面的验收范围；保持当前分支，不提交推送。

仅 Android 实施，iOS 本轮不变。外部系统界面（如系统键盘）由系统控制。

结果：0.6.62（76）已安装荣耀；隔离字体缩放、实际 Activity 横竖屏、模式切换及两种播放器控件回归通过。手机 font_scale 始终为 1.0。测试包已清理，主 App 保留标准模式；未提交推送。详见 [固定字号验收](../../requirements/Android固定字号验收.md)。
