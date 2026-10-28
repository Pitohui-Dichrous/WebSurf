# WebSurf 使用与构建

## 使用

安装 `WebSurf-0.4.0.apk`。首次打开为 `https://www.baidu.com`。右下角菜单 → 设置 → 主页，可保存其他网址；点击底部主页图标打开。

点击网页视频播放后，WebSurf 在原视频位置接管，随页面滚动和裁剪。默认不会弹出小窗；后台自动播放的视频仅被记录，不会凭加载顺序抢占。每个视频独立管理，切换视频可点击原网页对应视频，或在播放器的更多菜单中选择。

播放器提供播放/暂停、进度、画质、倍速、静音、下载、全屏和手动浮窗。画面较窄时可从更多菜单进入画质选择。横向视频进入全屏自动横屏，退出恢复原方向。全屏支持锁定、左右滑动调进度、左侧上下滑动调亮度、右侧上下滑动调音量；长按画面临时 2 倍速，松手恢复。浮窗顶部可拖动。三种显示模式共用同一个播放实例；更多菜单提供前后 15 秒跳转。原位播放时可以直接从画面纵向滑动页面，松手后保留惯性；视频经过页面顶部时随滚动距离平滑展开并吸顶，避开地址栏。反向滑动会沿原路径恢复原位。已吸顶后暂停保持位置，未播放的视频不会主动吸顶。菜单另提供 Android 系统小窗。

点击播放器关闭或底部“网页播放”会把进度交还该网页视频，保留网页、滚动和输入。该视频暂时不再自动接管；其他视频仍可正常选择。在网页切换清晰度后，点击底部“接管播放”或视频下方右侧的同名按钮即可恢复，无需刷新整页。网页接管按钮与底部按钮均跟随应用的浅色/深色设置；网页按钮占用视频下方独立一行，不覆盖画面或网页视频控制。设置中的“自动接管视频”是全局开关；关闭自动接管后仍可手动接管。

接管后的画质菜单只列出当前视频可用的选项。HLS 提供多档轨道时，在同一个播放器内切换，保留播放位置、暂停状态和音轨；“自动”由播放器根据网络等条件选择。同一网页 video 明确列出带清晰度标签的多个 `<source>` 时，菜单显示“网页”来源，切换保留逻辑进度、播放/暂停意图、倍速与音量。更换 MP4 等媒体地址需要重新读取新的视频流，不代表复用旧画质的缓冲。只有当前视频所属的来源参与选择，不把嗅探列表中其他视频或广告的地址混为画质选项。

选择可识别的数字清晰度后，应用会记住首选值；设置 → “首选画质”也可提前指定。后续接管时，如果视频公开了对应轨道或来源，优先选择不高于首选值的最高档；全部高于首选值时选择最低档。即使网页刷新后默认变回 360p，再次接管仍会尝试恢复首选画质。此功能不能让网站提供本来没有公开的档位；只有“HD”等非数字标签时可手动选择，但不会记为数字首选值。设置中的“自动 / 跟随网页”可清除固定画质偏好。

网站使用专有画质菜单、没有公开可用选项时，点画质对话框中的“网页画质”返回原网页操作，再点“接管播放”。这不会刷新网页。首次从网页切到原生播放器时，页面、进度等状态可以保留，但 Android WebView 已缓冲的数据和解码器不能直接转交给当前的 Media3 实现，因此仍可能重新请求、缓冲视频；本版本不承诺零缓冲接管。

正在接管时，网页的播放、暂停、进度和结束事件与内置播放器同步。片前广告结束后，网页脚本可按原有逻辑切换到正片。此实现针对可直接访问的 HTTP(S) 视频；无法可靠绑定到某个视频的媒体源不会猜测替换。

原位接管发生原生播放错误时，应用自动移除覆盖画面并恢复原网页 video 的进度和播放意图，不刷新网页，也不向网页伪造 MediaError。同一失败源不会反复自动接管；仍可手动重试，且该 video 切换到新来源后可继续接管主视频。用户主动选择“网页播放”则会保持网页模式，直到再次手动接管。从资源列表独立打开的视频没有对应网页元素，失败时仍保留错误与重试入口。

网页资源面板持续收集当前标签页的请求地址、DOM 媒体与可见响应类型，按视频、音频、图片和其他分类。分类按钮与资源列表之间保留独立间距。列表提供网页封面、可提取的视频帧或当前播放画面的缩略图，并明确标出预览来源。清晰度显示为如“720p · 1280 × 720”；HLS 主列表的可选清晰度与最高档单独标明，不能识别时显示“清晰度待识别”。预览只在列表可见时异步读取，HLS 预览不下载视频分片，未提供封面的 HLS 可在接管播放后使用实际画面。每页最多保留 400 个地址，但面板不再显示该说明页脚。点击条目可以预览、下载、复制链接或查看完整地址。资源嗅探是被动观察，不要求重新加载网页；部分资源仍需先播放或滚动才会出现。

顶部下载图标打开任务列表。普通文件按原字节保存；m3u8 先下载分片，随后合并为真正的 MP4。主播放清单默认选最高带宽版本；单一画质清单保留该画质。合并不提高画质，也不强制重新编码。Android 10 及以上保存到系统 `Download/WebSurf`。Android 8–9 使用应用专属下载目录，可从任务列表打开文件。音视频文件可在应用内播放。

下载支持后台通知、取消、失败原因和重试。重试从头请求；不承诺断点续传。系统终止应用后，未完成任务会显示中断，用户可重试。下载期间应保留足够空间供缓存和最终文件使用。

## 范围

浏览器使用 Android 系统提供的 Chromium WebView，实际内核版本可在设置查看；没有把完整 Chrome APK 嵌入应用。系统 WebView 可独立更新。普通页面滚动使用 WebView 原生回调同步播放器；iframe 内部滚动、fixed/sticky 和复杂 CSS 容器依靠网页逐帧更新，不能保证与 QQ 的私有内核完全相同。

当前版本不处理 DRM/SAMPLE-AES、仍在进行的直播、HLS 不连续时间轴或中途更换初始化片段。部分 blob/MSE、私有播放器、仅 POST 返回的资源及站点专有授权流程可能无法导出；嗅探到地址不等于该地址一定可以下载。DASH MPD 可作为网页资源被发现，但不提供 DASH 合并导出。

## 构建

需要 Java 17、Android SDK 35 和网络上的 Maven/Gradle 依赖。设置 `JAVA_HOME` 与 `ANDROID_HOME` 后，在项目目录执行：

```powershell
.\scripts\build.ps1 :app:assembleDebug
```

脚本优先使用已有环境变量，其次寻找本项目 `.tools` 和本机 `E:\MediaMaster\.tools` 中的现有工具链。Gradle Wrapper 固定为 8.11.1，并校验下载摘要。首次构建需要联网。

签名版构建：

```powershell
.\scripts\build.ps1 :app:assembleRelease
```

本机发布签名保存在 `.tools/signing/`，请保留以便覆盖升级。源码压缩包不包含签名私钥、工具缓存、原始 QQ 浏览器 APK 或反编译代码；没有签名配置时，release 构建产物为未签名 APK，debug 构建使用独立包名 io.websurf.app.debug，可与正式版共存。

## 测试

```powershell
.\scripts\build.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
node --test scripts/test_sniffer.mjs
python scripts/fixture_server.py
```

浏览器集成测试要求设备能够访问本机测试服务器；模拟器可用 `adb -s emulator-5560 reverse tcp:8765 tcp:8765`。明确指定测试设备，再安装两份 debug APK 并运行：

```powershell
adb -s emulator-5560 shell am instrument -w -e class io.websurf.app.PlayerRecoveryTest,io.websurf.app.SourceQualityTest,io.websurf.app.QualitySelectionTest,io.websurf.app.ResourcePanelLayoutTest,io.websurf.app.BrowserFlowTest,io.websurf.app.VideoControlsTest,io.websurf.app.ScrollPlaybackTest,io.websurf.app.ThemeResourceUiTest,io.websurf.app.ResourcePreviewTest,io.websurf.app.download.DownloadEngineInstrumentedTest,io.websurf.app.UiCaptureTest io.websurf.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`scripts/generate_fixtures.py` 可重新生成浏览器测试媒体，需要本地 FFmpeg。测试夹具仅放在 androidTest 中，不会进入正式 APK。
