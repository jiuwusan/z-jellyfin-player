# Android 账号语言偏好与官方选轨

2026-11-23，Android 0.6.34（48），荣耀 BVL-AN00。Android 和原版、大字版交互原型更新，iOS 未实施。沿用原包名、签名、代理、授权、画质和当前分支。

## 设置与生效范围

- 新增「首选的音频语言」「字幕语言偏好」。首次尚未连接的表单初值均为 Chinese；已有账号进入设置时读取服务端真实偏好，包括未指定，不自动覆盖为中文。
- GET Users/{当前账号ID} 读取 Configuration；用户确认保存后重新读取最新完整配置，只修改 AudioLanguagePreference 和 SubtitleLanguagePreference，经 POST Users/Configuration?userId=当前账号ID 保存。新旧值相同则不发送 POST。
- 点击「保存语言偏好」直接保存，不再要求确认密码；更换或清除订阅仍受确认密码保护。接口使用现有代理和鉴权，服务端拒绝或网络失败时不提示保存成功。
- 2026-11-24 荣耀覆盖安装验证：保留现有两项 Chinese，点击保存直接显示「语言偏好已保存」，无确认密码弹窗；点击「清除订阅」仍弹出确认密码，随即取消，订阅保留。Android 构建通过；iOS 未实施本次调整。
- 账号偏好在同账号其他客户端共享。只打开设置、自动登录或播放不会写入偏好。
- 读取偏好期间禁用语言选项；读取失败仍可保存连接和画质，且不会把缓存或默认 Chinese 写回账号。首次连接失败、重试及返回草稿保留是否允许同步语言的标记。
- 未改语言时保留服务端原始值，不把区域码或旧别名改写为泛化语言；未列出的账号语言单独展示，避免伪显示为 Chinese。本地加密语言字段只是表单草稿缓存。

## 自动选轨与字幕

App 使用 PlaybackInfo 返回的 DefaultAudioStreamIndex、DefaultSubtitleStreamIndex，不再根据轨道 Language 执行第二套语言筛选。服务端负责语言偏好、字幕模式、记忆轨道选择及默认轨策略。播放中手动选择仍明确指定索引；关闭为 -1。

字幕投递沿用已验证规则：文本由手机绘制，图片由 Jellyfin 烧录。切换时保留位置、暂停状态、音轨和画质。

## 检查

- 新受控检查在旧实现以“App overrode the server's audio choice or disabled-subtitle mode”失败，修复后 languages 为 ALL_PASS。
- 受控服务默认英文音轨并关闭字幕，即使 App 草稿为 Chinese，自动播放也保留英文和关闭；手动选择中文音轨和 PGS 则正常生效。
- 读取账号英文偏好、写入 Chinese、相同值跳过 POST 通过；字幕模式 None、记忆字幕选择、默认音轨开关和未知字段 OtherSetting 保持不变。
- 读取结果缺少 Configuration 时不发送更新；服务端拒绝保存返回 403 时向调用者报错，原配置不变，两项失败检查通过。
- 新字段 JSON 及实际 Keystore 加密往返、旧配置缺字段默认值通过。测试写入使用独立 SharedPreferences，随后清理，不替换设备原连接配置。
- 真机设置页等待服务端偏好读取完成，选择值与实际账号返回一致；读取设置前后原加密连接数据相同。
- 主 APK、测试 APK 构建及原版、大字版原型 JS 语法检查通过。字幕受控检查和 subtitles-real 均 ALL_PASS；《人类清除计划2》默认中文 PGS 正常，切英文图片轨、关闭及恢复自动均保持 600000 ms；《魅影神捕》文本 cue 通过。硬解 c2.qti.avc.decoder，原连接配置逐字节不变。结果保存在本机 verification/language-server。

本轮账号更新接口在本地环回服务验证，真实账号只读，未为验收改写其偏好。真实播放会正常更新观看进度。未修改 Jellyfin 容器、其他用户或媒体文件；未执行全量 Lint，既有 DisplayCutout NewApi 限制不在本轮处理。

交付前清理测试包、独立测试偏好文件和设备截图，保留主应用及原配置。APK 复制到系统下载目录，未自动提交或推送。
