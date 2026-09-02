# 饮水机遥控

这是一个从 `Duoheshui` 通信流程中独立出来的精简 Android 应用，只实现以下功能：

- 手机验证码登录或帐号密码登录
- 扫码或手动绑定热水、冷水设备
- 远程发送热水、冷水出水指令
- 查询并刷新账户余额

应用使用 Kotlin、Jetpack Compose 和 Material Design 3。业务状态由 `ViewModel` 管理，登录会话与设备码仅保存在本机，密码不会持久化；退出登录会清除账户令牌，但保留设备绑定。

## 打开与构建

用 `/home/young/Downloads/android-studio/bin/studio.sh` 打开本目录：

```text
/home/young/WaterDispenserRemote
```

也可以在终端中构建：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug \
  -Dorg.gradle.java.home=/home/young/Downloads/android-studio/jbr
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 协议说明

应用兼容参考项目使用的小天同学旧接口与 DES/CBC 加密格式。旧服务目前使用 HTTP，因此网络安全配置只对以下两个指定域名开放明文流量，其他域名仍默认禁止：

- `newxiaotian.tianji-inc.com`
- `iot.tianji-inc.com`

远程出水可能造成溢水或烫伤，UI 会在每次发送指令前要求确认。
