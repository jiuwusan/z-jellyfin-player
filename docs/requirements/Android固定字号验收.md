# 固定 App 字号验收

Android 0.6.62（76），荣耀 BVL-AN00。当前手机系统字号为 1.0，仅只读核对，没有改动系统设置。

受控测试通过隔离 Context 模拟 0.85、1、1.3、1.6、2 倍系统字号，用 Activity 同样的原生 ContextThemeWrapper 覆盖机制验证 12、14、24、28、48 sp 的实际像素字号，以及输入框、下拉选项、对话框上下文。模拟 2 倍时原始 sp 换算确实变大，App 字号仍与 1 倍基线一致；标准和长辈模式仍有不同字号。

覆盖配置仅包含 fontScale=1，densityDpi、方向和屏幕宽高字段保持未指定。模拟上下文的 dp 和显示密度不变。主 Activity 真机请求横屏后再恢复竖屏，资源方向正常更新且 fontScale 保持 1；没有固定横竖屏配置。

初次尝试在基础上下文另建字体配置上下文，测试发现它会丢失已有上下文覆盖的屏幕参数；最终改为 Activity 原生 applyOverrideConfiguration，在 attachBaseContext 之后、首次读取界面资源前应用。没有使用共享 Resources.updateConfiguration，也不修改系统 settings。

本机日志：verification/font-scale/ 下 red.log、diagnostic.log、build.log、check.log，以及模式和播放器控件回归日志。隔离字体模拟不等同于实际修改手机系统字号后的现场验收；手机系统字号始终保持原值。App 字号政策仅控制本 App，外部系统键盘和权限窗口仍由系统控制。服务端烧录字幕与字体文件不变。未修改订阅、代理、服务配置或 iOS。

最终检查 final-check.log 通过；ui-mode、fullscreen-standard、fullscreen-controls 回归均通过。测试包已卸载，主 App 0.6.62 保留原订阅与标准模式。未提交、推送。
