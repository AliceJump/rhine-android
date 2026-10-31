# Android 平台配置

## Android 8.0 兼容

最低支持 API 26。默认主题不声明 API 27 才提供的 `android:windowLightNavigationBar`；状态栏和导航栏图标外观由 `MainActivity` 中的 `WindowCompat` 根据当前主题设置。

## 本地数据与备份

曲库索引保存文档 URI，依赖当前设备上的文件夹授权。本项目不提供数据迁移或恢复功能，换机后重新选择目录并扫描。

Manifest 保留 `android:allowBackup="false"`。`res/xml/backup_rules.xml` 为 Android 11 及更早版本排除全部应用数据；`res/xml/data_extraction_rules.xml` 分别为 Android 12 起的云备份和设备间传输排除全部应用数据。两份规则覆盖普通应用存储、设备保护存储及应用外部文件目录；外观偏好也不迁移。

Android 官方说明部分设备上的 `allowBackup="false"` 不会单独禁止设备间传输，因此需要明确配置两类提取规则。参见 [Auto Backup 配置](https://developer.android.com/identity/data/autobackup) 和 [Android 12 备份行为变化](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore)。

## 媒体服务的导出入口

`PlaybackService` 保持 `exported="true"`，供系统媒体控制器连接。该入口遵循 [Media3 后台播放配置](https://developer.android.com/media/media3/session/background-playback#declare-the-service-in-the-manifest)，不设置会妨碍兼容控制器的自定义绑定权限。

访问控制由现有 `PlaybackService.SessionCallback` 执行：

- `onConnect` 只接受本应用 UID，或 Media3 判定为可信任的控制器。
- 外部可信任控制器不能设置媒体项或修改播放队列，也不会获得自定义会话命令。
- `onAddMediaItems` 再次验证调用方 UID，并限制可交给播放器的 URI 类型。

可信任控制器的含义见 [Media3 访问检查](https://developer.android.com/media/media3/session/background-playback#identify-trusted-controllers)。

Lint 的 `ExportedService` 警告会继续显示：它检查 Manifest 中是否有绑定权限，无法证明上述会话回调的访问控制。此警告已按媒体服务用途审阅，未全局关闭或添加 suppress。依赖新版本提示也保留，不为消除提示改动已验证的依赖组合。
