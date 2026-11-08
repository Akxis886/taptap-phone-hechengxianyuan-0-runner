# 合成仙缘 · 手机本地自动游玩

Android 本机 `app_process` 程序：截屏 → 轻量标签匹配 → expectimax → 滑动。运行时不传图片到电脑或模型，不需要 Python、root、APK 安装。DSH 只负责启动；启动后循环在 Android shell 中运行。

当前为实机验证版，**尚未达到“所有安卓手机、自动广告领奖全部验收通过”**。

## 已验证与限制

- REDMI K80 / Android shell UID 2000：经典模式连续 277 步，平均约 1.0 s/步（screencap + 模板匹配 + expectimax）。
- 棋盘定位改为**运行时测量**：按棋盘青色像素扫出上下边界（经典约 332 px/格，无尽约 354 px/格），不再依赖固定偏移；滑块名条在格子底部，单独定位。
- 激励视频广告：经典模式 2 席 → 3 席闭环成功（点"看视频" → 等约 33 s 播完 → 点右上青色"退出并领奖" → 校验席位 +1）。
  过程中不点"去体验/我要加速"，那会跳第三方 App 并触发系统"是否允许打开"确认弹窗，只能人工点。
- 同一真实棋盘缩放到宽度 720 / 1080 / 1440 / 2160，16 格输出一致（缩放回放测试，不等于四台真机验证）。
- 等级模板覆盖 2 到 4096；疑似未知标签、连续读盘失败、前台切走时停止。更高等级、不同游戏版本、横屏、平板和字体布局尚未验证。
- 无尽模式：棋盘识别已验证（短局，只含 2 个方块）；经典 → 无尽的自动切换代码已写但未实机验证。
- 广告按钮显示"视频进行中"时，自动重载对局的校验可能失败并停止。踩坑细节见 `NOTES.md`。

## 手机 DSH 一条命令

前提：手机 DSH 本机 Linux 终端内 `adb devices` 必须能看到本机且状态为 `device`。DSH App 内的无线配对可能使用独立密钥，不代表 Linux 终端的 adb 客户端也已配对。没有 adb 时先在 DSH Ubuntu 安装 `adb`；首次配对/授权由手机用户完成。

发布包里的 `one-shot.sh` 是自包含启动器。将它下载到 DSH 本机终端可访问的位置，然后：

```bash
bash one-shot.sh
```

它会校验并将约 230 KB 程序推到**同一手机**的 `/data/local/tmp/taptap-phone`，启动后台进程。运行后可以断开电脑；广告仍需网络。

指定已配对端点：`DSH_ADB_ENDPOINT=127.0.0.1:端口 bash one-shot.sh`。连接多台设备时显式设置 `ANDROID_SERIAL`，脚本不会擅自选择第一台。

```bash
bash one-shot.sh check                  # 只读自检，不触摸
bash one-shot.sh start --skip-ads       # 纯游玩
bash one-shot.sh start --steps 100      # 最多 100 步
bash one-shot.sh status                 # 状态与末尾日志
bash one-shot.sh stop                   # 协作停止，当前系统调用结束后退出
```

若通过已授权的原生 Android shell 执行，已部署后的短命令为：

```sh
sh /data/local/tmp/taptap-phone/run.sh start
```

不能把它直接当成普通 Ubuntu shell 命令；普通 App UID 不具备注入触摸权限。不要通过改命令绕过 DSH 的用户授权机制。

## 行为与日志

默认最多两小时，不自动重开、不会清游戏数据或改分辨率。广告路径：等视频播完 → 点右上青色"退出并领奖"；不点体验/加速/下载类按钮，避免跳转第三方和系统授权弹窗。席位数量真正增加才记为奖励成功。

手机日志：`/data/local/tmp/taptap-phone/run.log`。`STOP` 文件可请求停止。并发实例通过文件锁拒绝。

## 重建

需要 JDK 和 Google 官方 R8 8.3.37（不包含在发布包）。

```powershell
./build.ps1 -Jdk '你的JDK目录' -R8Jar '你的r8.jar路径'
```

源码在 `src/PhoneBot.java`，DEX 最低 API 26。识别模板为从本项目历史游戏画面提取的二值标签特征，不包含完整截图、角色立绘、账户数据或凭据。`one-shot.sh` 内嵌经过 SHA-256 校验的压缩运行包；源代码可审阅。
