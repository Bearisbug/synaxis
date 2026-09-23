# 聊天警告的真客户端取证（REQ-PROD-009，2026-09-23）

对应 `docs/DESIGN-PROD.md` `ADR-PROD-009` 标「未核实」的一项：`JoinGame` 的 `enforcesSecureChat=false`、聊天以 `SystemChat` 转发时，真客户端会不会出「聊天无法验证」提示。用例是 `docs/TEST.md` 的 `TC-PROD-083`。

环境：`client-fabric/gametest-env/run.sh`，镜像 `synaxis-mc-client:dev`（Xvfb + llvmpipe），客户端 26.2 + 本仓模组，离线开发账号 `Player0`；服务端是本分支 `:server-app:installDist` 的产物，端口 25622，`java -cp "lib/*:chatrelay.jar" io.github.bearisbug.synaxis.app.Main` 起服。`ChatRelayPlugin.java` 是取证用的一次性插件，把聊天转发给发送者所在实例的全部玩家，不进构建。`SYNAXIS_SPIKES=chat` 打开 `SpikeClientGameTest#chat`：进服即读 `ToastManager` 里有没有 `UNSECURE_SERVER_WARNING`（报告行 `C1`），20 tick 后发一条聊天，再 40 tick 截图 `chat-after-send` 并列出收到的系统消息与玩家聊天（报告行 `C2`）。

| 截图 | 服务端 | 模组 | `C1` / `C2` |
| --- | --- | --- | --- |
| `secure-chat-off-vanilla-toast.png` | `enforcesSecureChat=false` + 转发插件 | 不带 `ClientPacketListenerMixin` | `unsecure_server_toast=true`；`system_messages=[<Player0> hello from the gametest] player_chat=[]` |
| `secure-chat-off-mod-relayed.png` | 同上 | 带 `ClientPacketListenerMixin` | `unsecure_server_toast=false`；同上 |
| `secure-chat-off-mod-no-recipients.png` | `enforcesSecureChat=false`，不带转发插件 | 带 | `unsecure_server_toast=false`；`system_messages=[] player_chat=[]` |

结论：

1. 原版客户端只要服务端不强制安全聊天就弹「Chat messages can't be verified」提示。26.2 的 `ClientPacketListener#handleLogin` 在 `serverData != null`、本连接没弹过、且 `services.canValidateProfileKeys() && serverEnforcesSecureChat` 为假时加这条 `SystemToast`，与在线还是离线无关。只改服务端做不到「无警告」，模组的 `ClientPacketListenerMixin` 把 `handleLogin` 里唯一一次 `addToast` 拦掉。
2. 转发的聊天走 `SystemChat`，聊天栏左侧没有「不安全」标记，客户端也没收到玩家聊天包。
3. 对照：服务端改回 `enforcesSecureChat=true`、模组不带混入时，同一离线容器里 `C1 unsecure_server_toast=false`（截图未归档）。正版账号路径（`REQ-PROD-002` 未落地）没有测到。
