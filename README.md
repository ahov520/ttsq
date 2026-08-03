# ZaiTTS Android

把 [aahl/zai-tts](https://github.com/aahl/zai-tts)(智谱 GLM TTS → OpenAI Speech API)移植为 Android 应用:**一键启动本地 TTS 服务**,作为「阅读」App 的朗读引擎。

## 功能

- 一键启动/停止本地 HTTP 服务(前台服务 + 通知栏,默认端口 `8823`)
- 接口与上游完全一致:
  - `POST /v1/audio/speech` — OpenAI Speech API 兼容,流式返回 WAV
  - `GET /v1/models` — 获取音色列表(含你在 audio.z.ai 克隆的音色)
- 内置生成「阅读」App 朗读引擎配置,一键复制导入
- 免登录鉴权转发:只需配置一次 `ZAI_USERID` / `ZAI_TOKEN`
- 打开 App 自动启动服务、开机自启(可选)

## 使用步骤

### 1. 获取 ZAI_USERID 和 ZAI_TOKEN

1. 电脑浏览器打开并登录 [audio.z.ai](https://audio.z.ai)
2. 按 `F12` 打开开发者工具,在**控制台(Console)**执行:
   ```js
   localStorage['auth-storage']
   ```
3. 从输出中复制 `user_id` 和 `token`(很长的 `eyJhbGc...` 字符串)

### 2. 配置并启动

1. 打开 ZaiTTS,填入 `ZAI_USERID` 和 `ZAI_TOKEN`
2. 点击 **「一键启动服务」**,状态变为「服务运行中」并显示 `http://手机IP:8823`
3. 可点 **「测试合成」** 试听,验证配置正确

### 3. 在「阅读」App 中导入

1. 启动服务后,点击 **「复制阅读配置」**
2. 打开阅读 App → 朗读设置 → 朗读引擎管理 → **导入** → 粘贴
3. 选择「ZaiTTS-智谱」作为朗读引擎即可

配置长这样(IP 自动填你手机的局域网地址):

```json
[
  {
    "name": "ZaiTTS-智谱",
    "url": "http://192.168.1.5:8823/v1/audio/speech,{\"method\":\"POST\",\"body\":{\"input\":\"{{speakText}}\",\"speed\":{{((speakSpeed+5)/10).toFixed(1)}},\"voice\":\"system_002\"}}",
    "contentType": "audio/wav",
    "concurrentRate": "1"
  }
]
```

> 阅读内置语速 5 对应 TTS 速度 1.0,每增减 1 对应 0.1。在 App 内切换默认音色后,重新复制一次配置再导入即可。

### 4. 手动 curl 验证(可选)

```bash
curl -X POST http://手机IP:8823/v1/audio/speech \
  -H "Content-Type: application/json" \
  -d '{"input":"你好世界","voice":"system_002","speed":1.0}' \
  -o out.wav
```

## 内置音色

| voice | 说明 |
|---|---|
| `system_001` | 活泼女声 |
| `system_002` | 温柔女声(默认) |
| `system_003` | 通用男声 |

克隆音色:在 [audio.z.ai](https://audio.z.ai) 完成克隆后,点 App 内 **「获取我的音色列表」** 查看 `voice_id`,填入「默认音色」并重新复制配置。

## 构建 APK

不需要本机安装任何 Android 环境,用 GitHub Actions 构建:

1. 把本项目推送到你自己的 GitHub 仓库
2. 打开仓库的 **Actions** 标签页 → 选择 **「构建 APK」** → **Run workflow**
3. 构建完成后在运行详情页底部的 **Artifacts** 下载 `ZaiTTS-debug-apk`

Debug 包使用自动生成的调试证书签名,可直接安装。

> 也可以用 Android Studio 打开本项目,`Build → Build APK(s)` 本地构建。

## 常见问题

- **阅读里朗读没声音 / 报网络错误**:确认 ZaiTTS 服务正在运行、手机与阅读在同一台设备上(同一局域网),并检查端口未被占用。服务地址就是 App 顶部显示的地址。
- **提示 401 / 鉴权失败**:`ZAI_TOKEN` 过期了,重新执行步骤 1 获取并更新。
- **锁屏后服务被杀**:在系统设置里给 ZaiTTS 关闭「电池优化」,并允许后台运行;App 已申请前台服务 + 部分唤醒锁,多数国产 ROM 还需手动加白。
- **速度换算**:阅读语速 N → TTS 速度 `(N+5)/10`。

## 致谢

- [aahl/zai-tts](https://github.com/aahl/zai-tts) — 原始服务实现,本项目为其逻辑的 Kotlin/Android 移植
- [阅读 legado](https://github.com/gedoor/legado) — HttpTTS 朗读引擎协议
