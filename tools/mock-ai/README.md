# 穷图 AI 模拟服务器

不花钱、不发真实请求地测试 AI 功能。只用 Python 标准库，按 8 家服务商的接口路径和返回格式（usage、base64 图、下载地址、异步轮询）回一张"改过"的图：

- 发过去的是 PNG（OpenAI / 自定义）：颜色反相，左上角盖一块橙色。
- 发过去的是 JPEG（其他几家）：颜色变得很浓、整体发灰（直接改 JPEG 量化表，不解码）。
- 文生图（贴纸）：橙色渐变图；要透明背景时是透明底上的橙色圆。

## 启动

```bash
python3 tools/mock-ai/server.py          # 监听 0.0.0.0:8765，换端口用 MOCK_AI_PORT=xxxx；MOCK_AI_FULL=1 打出完整指令
```

每个请求在终端打一行：时间、方法、路径、服务商、提示词前 60 个字。不打印 Authorization / Key。

## 在模拟器里接上

安卓模拟器里 `10.0.2.2` 就是宿主机，App 的 `network_security_config.xml` 已放行它的明文 HTTP。

打开 App → 主页底部"AI 修图" → 进入某家服务商的页面：

1. API Key 随便填一个（比如 `test`）。填 `bad` 会被当成错误的 Key（401），可以用来测报错。
2. "接口地址"改成下表里的地址。
3. 点"测试连接"，通过后回上一页把它设为当前使用。

| 服务商 | 接口地址（Base URL） | 走的接口 |
|---|---|---|
| OpenAI | `http://10.0.2.2:8765/openai/v1` | `POST /images/edits`（multipart）、`POST /images/generations`、`GET /models` |
| Google Gemini | `http://10.0.2.2:8765/gemini/v1beta` | `POST /interactions`、`GET /models` |
| MiniMax | `http://10.0.2.2:8765/minimax/v1` | `POST /images/edits`（JSON）、`POST /images/generations` |
| xAI Grok | `http://10.0.2.2:8765/grok/v1` | `POST /images/edits`、`POST /images/generations`、`GET /image-generation-models` |
| 通义千问 | `http://10.0.2.2:8765/qwen/api/v1` | `POST /services/aigc/multimodal-generation/generation`，结果是下载地址 |
| 豆包 Seedream | `http://10.0.2.2:8765/doubao/api/v3` | `POST /images/generations` |
| FLUX | `http://10.0.2.2:8765/flux/v1` | `POST /{型号}` → 轮询 `polling_url`（第一次 Pending，第二次 Ready）→ 下载 |
| 自定义（OpenAI 兼容） | `http://10.0.2.2:8765/custom/v1` | 同 OpenAI，但只返回 `url`，测下载这条路 |

路径的第一段（`/openai`、`/qwen` …）决定按哪家的格式回，`-` 后面的部分忽略。要测海外地域的价目，把地址写成 `http://10.0.2.2:8765/qwen-intl/api/v1`（千问新加坡）或 `http://10.0.2.2:8765/doubao-byteplus/api/v3`（豆包海外，不估价）——App 按地址里有没有 `-intl` / `byteplus` 判断。

### 模拟出错

- 指令里带 `#审核`：按那家的格式回一次内容审核拒绝（FLUX 是轮询时回 `Request Moderated`）。
- Grok 选旧型号 `grok-imagine-image` 并打开"高清输出"：第一次带 `resolution: 2k` 会被拒（400），App 应自动去掉它再发一次，终端里能看到两行请求。
- Key 填 `bad` 或不填：401。

### 测试连接

不能列型号的几家（MiniMax、千问、豆包、FLUX）测试连接时发一个空请求，模拟服务器回"缺参数"（400 / 422），App 据此判断 Key 被接受了。

## 用 curl 自测

在宿主机上（地址换成 `http://127.0.0.1:8765`）：

```bash
# OpenAI 改图
curl -s -H "Authorization: Bearer test" -F model=gpt-image-2.6-sunburst -F prompt=测试 -F size=1024x1024 \
  -F "image[]=@photo.png;type=image/png" http://127.0.0.1:8765/openai/v1/images/edits | head -c 200

# FLUX：提交后按返回的 polling_url 轮询
curl -s -H "x-key: test" -H "Content-Type: application/json" \
  -d '{"prompt":"测试","images":["'"$(base64 -i photo.jpg)"'"]}' http://127.0.0.1:8765/flux/v1/flux-3-image
```
