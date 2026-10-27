# 泥轻助手（郑州轻工业大学）

一个面向郑州轻工业大学的 Android 校园工具，把分散在教务与校内网页里的信息整理成原生 App。

> 非官方项目，个人学习作品；接口来自学校网页，学校改版后可能失效。

## 功能

**课表**
- 教学安排周表，学期 / 教学周切换，单双周解析
- 开学日期可手动设置，自动计算当前周
- 自定义日程（可与教务课混排、撞课提醒）
- 课程总览、格子内容与密度可调
- 自定义背景图（模糊、浓淡、导航栏透明）
- 课程配色：糖果色 / 粉彩 / 浓郁三种预设 + 整体深浅微调

**今天**
- 今日课程竖排时间轴（最近上完 → 正在上 → 未开始）
- 日期 + 天气概览（定位到市/区，定位失败保留上次位置）
- 日历（近三周折叠 / 整月展开，可滑动或点箭头换月，节假日红色）

**学习**
- 成绩：按学期分组，正序 / 倒序，平时 / 期中 / 期末 / 综合
- 考试：原生列表，必要时跳学校网页
- 学籍：学号、姓名、身份证号、考生号、专业、院系等，敏感字段默认脱敏，本地加密缓存
- 空闲教室：东风 / 科学 / 禹州，教室类型、周次、星期、起止节

**桌面小组件**
- 下一节课（2×2）
- 今日课程（2×2 正方形，深色卡片）
- 今明课表（4×2）

## 登录

- 主：学校统一身份认证（短信），内嵌 WebView，登录后自动抓学号
- 备：i轻大扫码登录
- 会话加密保存，退出登录清除

## 构建

环境：JDK 17、Android SDK 36、Gradle 8.9。

```powershell
$env:JAVA_HOME="...\tools\jdk17"
$env:ANDROID_HOME="...\tools\android-sdk"

.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

Release 签名读取仓库根目录 `keystore.properties`（已 gitignore）：

```properties
storeFile=...
storePassword=...
keyAlias=...
keyPassword=...
```

## 技术栈

Kotlin + Jetpack Compose (Material 3) · Room · DataStore · EncryptedSharedPreferences · WorkManager · OkHttp · jsoup · kotlinx.serialization · zxing

## 目录结构

```
app/src/main/java/com/zzupocket/app
├── core/          基础设施（网络、加密、学校配置、AppGraph）
├── data/          数据层
│   ├── auth/      统一认证 / 令牌
│   ├── qingguo/   青果教务（课表、成绩、考试、学籍、空教室）
│   ├── prefs/     设置项
│   ├── home/      天气、节假日、定位
│   └── repo/      本地数据仓库
├── domain/        领域模型
└── ui/            Compose 界面（today / schedule / classroom / grades / exams / profile ...）
```

## 数据与隐私

- 不保存密码，只保存学校下发的会话（加密存储）
- 课表、成绩、学籍只存本机数据库，无自建服务器
- 学籍敏感字段默认脱敏
- 仓库中不提交个人成绩、身份证号、Cookie、学号截图

## 已知限制

- 电费 / 校园卡：入口「待开发」（完美校园云缴费是一次性 code，无法自动登录）
- 研究生系统：界面已隐藏
- 扫码登录：个别账号需补填一次学号
- 空闲教室：暂不支持按楼栋筛选

更多细节见 [docs/轻工大移植说明.md](docs/轻工大移植说明.md)。
