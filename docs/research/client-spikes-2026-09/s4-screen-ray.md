# S4 屏幕坐标 → 世界射线：实跑记录（2026-09-23）

README 的 S4（spike ②(b)）在无头容器里跑完：Docker + Xvfb + Mesa llvmpipe，真 Minecraft 26.2 客户端，
Fabric Loader 0.19.5，连玩法模式的 server-app（`rts-512`，玩家出生在 256, 40, 256）。光标只经
`TestInput.setCursorPos` 进客户端，没有碰宿主机的鼠标。同一份代码连跑两轮，像素表逐字相同。

## 结论

判据三条全部通过：

- **高亮落在光标下那一格**：7 种相机 × 5 个点 = 35 个点，射线命中点的标记与光标的像素误差 33 个是
  0.0 px，2 个是 0.1 px；35 个点里 33 个光标四面都被命中面包住，另 2 个恰在格线上（命中点离格边
  0.01 与 0.02 格），见下文数据表。
- **四角与中心误差一致**：正交四种形态的四角与中心都是 0.0 px，没有「越靠边越偏」。
- **移动中不滞后**：相机按 1 格/tick 平移，在 partialTick = 0.25 / 0.5 / 0.75 各截一帧，按本帧相机求的
  射线误差 12 帧全是 0.0 px；同一光标若按 tick 时刻的相机求射线（即忽略 partialTick），落点偏
  6～25 px，落在平地上时偏移量与 (1 − partialTick) 成正比。

这三条成立有两个前提，都是本轮跑出来的，6b 必须照做：

1. **26.2 的投影是反向深度（reverse-Z）**。`Projection#getMatrix` 把 zFar、zNear 对调后交给 JOML
   的 `setPerspective` / `setOrtho`，所以 NDC z = 1 是近平面，远平面在 z = −1（OpenGL 深度范围）或
   0（`DeviceInfo#isZZeroToOne` 为真）。照教科书拿 z = −1 当近平面，射线起点会落到远平面、方向朝
   相机背后：第一次实跑就是这样，起点 y = −1728，35 个点全部未命中。
2. **正交投影下，反投影用的矩阵必须和渲染用的是同一个**。`Projection#setupOrtho` 生成的盒子是
   [0, w] × [0, h]，S2 的相机只在 `Camera#extractRenderState` 里把渲染用的投影平移半帧居中，
   `Camera#getViewRotationProjectionMatrix` 缓存的仍是没居中的那个。不补这一步，每个点都差
   1101.5 px（= √(960² + 540²)，正好半帧）。本轮在 `CameraMixin` 里给
   `getViewRotationProjectionMatrix` 的返回值补上同一个平移后误差归零。

## 取矩阵与 partialTick 的落点

- **时机**：`GameRenderer#extract` 的开头。每帧的顺序是 `GameRenderer#update`（里面调
  `Camera#update`，按 `Camera#getCameraEntityPartialTicks` 放好相机）→ `GameRenderer#extract` →
  `GameRenderer#render`，在 extract 开头取到的就是这一帧要画的相机。gametest 的截图走的是同一对
  update / extract 调用，只是 DeltaTracker 换成 `DeltaTracker.DefaultValue`。
- **矩阵**：`camera.getViewRotationProjectionMatrix(new Matrix4f()).invert()`，对光标的
  (ndcX, ndcY, 1) 与 (ndcX, ndcY, 远平面 z) 各做一次 `transformProject`，两点都加上
  `camera.position()`，得到射线起点与方向；再 `level.clip(ClipContext.Block.OUTLINE)`。矩阵不含相机
  平移，与 README 的说法一致。
- **光标**：`MouseHandler#getScaledXPos(Window)` ÷ `Window#getGuiScaledWidth()` 得 [0, 1]，
  ndcX = 2x − 1，ndcY = 1 − 2y。报告里回读的光标像素与 `setCursorPos` 设的值逐点相等（40.0、1880.0、
  960.0 等，保留一位小数）。
- **partialTick**：相机跟随的锚点按 `lerp(getCameraEntityPartialTicks, 上一 tick, 这一 tick)` 放；射线
  只读 `camera.position()`，不自己再算一次插值，所以天然与这一帧对齐。
- **一个会误导验收的坑**：vanilla 的线条着色器 `rendertype_lines.vsh` 在投影前把视空间整体乘
  1 − 1/256（`VIEW_SHRINK`）。透视投影下这只是沿视线缩近、屏幕位置不变；正交投影下它把所有线条往画面
  中心拉，1920 宽的画面左右边缘理论值 920 / 256 = 3.6 px。实测（用描边画高亮的那次试跑，正交 h = 24，光标 40, 40）：
  命中面的左边线画在 x = 40～42，而按命中点坐标算，这条格边在 x = 37.3；右上角对称地差 4.7 px。
  方块描边与 gizmo 描边都走这个着色器，所以本轮的高亮改用填充面（四边形，不经过它），6b 在正交模式下
  的选中描边也要么用填充、要么顶点先乘 256/255 抵消。

## 数据

复现：见文末。数字来自 `client-fabric/gametest-env/s4_measure.py`：标记是命中点处一个 6 px 见方的
立方体，画在最上层，单独占一帧；误差 = 该帧里标记像素的质心 − 光标坐标。「四面」是另一帧里只画命中面
（黄色填充）时，从光标沿上下左右直走 400 px 以内碰到黄色的方向数，光标像素本身算在内。

屏幕 1920 × 1080，四角取离边 40 px 的点（tl 40,40 / tr 1880,40 / bl 40,1040 / br 1880,1040），中心
960,540。相机俯角都是 60°，yaw 0（朝 +Z），锚点是玩家脚下。

### 静止：7 种相机 × 5 个点

| 相机 | 含义 | tl | tr | bl | br | c | 四面 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 正交 d32 h24 | 拉近到极限（1 格 = 45 px） | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 5 点 4/4 |
| 正交 d64 h96 | S2 的默认形态（1 格 = 11.25 px） | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 5 点 4/4 |
| 正交 d36 h120 | 拉远（1 格 = 9 px） | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 5 点 4/4 |
| 正交 d240 h96 | 眼睛拉高到 208 格 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 5 点 4/4 |
| 透视 d16 fov70 | 贴近 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | tl 2/4，其余 4/4 |
| 透视 d48 fov70 | 拉高 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | tl 1/4，其余 4/4 |
| 透视 d64 fov30 | 缩放（fov 取选项下限 30） | 0.0 | 0.0 | 0.1 | 0.1 | 0.0 | 5 点 4/4 |

（单位 px；d 是锚点到眼睛的格数，h 是正交画面的竖向跨度格数。）

- 两个不满 4/4 的点命中的是 287.01, 40, 275.64 与 348.02, 40, 313.93，离格边 0.01 与 0.02 格，在屏幕上
  不到 1 px：光标就压在格线上，填充面的光栅化把这一列像素划给了邻格。
- 正交的命中点与解析值一致：h = 24 时 tl 与 tr 的命中 x 相差 40.88 格，1840 px × 24 / 1080 = 40.89；
  tl 与 bl 的命中 z 相差 25.66 格，1000 px × 24 / 1080 ÷ sin 60° = 25.66。
- 正交 d240 与 d64 h96 的 5 个命中点逐个相同：正交下抬高眼睛不改变画面，也不改变射线落点。但 d240 的
  整帧是天空色（截图 `s4-ortho-p60-d240-h96-c-face.png`）。地面离眼睛 240 格，超过渲染距离 10 区块
  （160 格），推断是雾把地形盖掉了，本轮没有单独关雾验证；这属于 S5 的地形问题，射线照样命中了看不见
  的地面。
- 眼睛更高或画面更宽的形态（正交 h192、透视 d64 fov70，前几轮试跑过）四角同样看不到地面，所以「拉远」
  一档取在 h120、透视「拉高」一档取在 d48，保证四角的地面还画得出来。

### 平移中：partialTick 是否取对

锚点每 tick 沿屏幕 x 方向移 1 格，先走 20 tick，再按指定的 partialTick 截图（`withDeltaTicks`）。
每个 partialTick 截两帧：一帧画按本帧相机求的命中点（品红），一帧画按 tick 时刻相机求的命中点（红）。

| 相机 | 光标 | partialTick | 本帧相机 | tick 时刻相机 |
| --- | --- | --- | --- | --- |
| 正交 d64 h48 | c | 0.25 / 0.50 / 0.75 | 0.0 / 0.0 / 0.0 | 17.0 / 11.0 / 6.0 |
| 正交 d64 h48 | br | 0.25 / 0.50 / 0.75 | 0.0 / 0.0 / 0.0 | 17.0 / 11.0 / 6.0 |
| 透视 d32 fov70 | c | 0.25 / 0.50 / 0.75 | 0.0 / 0.0 / 0.0 | 20.0 / 12.0 / 6.0 |
| 透视 d32 fov70 | br | 0.25 / 0.50 / 0.75 | 0.0 / 0.0 / 0.0 | 24.8 / 18.5 / 8.3 |

正交 h48 时 1 格 = 22.5 px，忽略 partialTick 的理论偏移是 22.5 × (1 − p) = 16.9 / 11.3 / 5.6 px，实测
17.0 / 11.0 / 6.0（标记 6 px，质心按整像素落）。透视两行不成比例的格子是落点碰上了石柱顶（y = 43），
深度变了。以 60 fps、20 tps 计，一帧是 1/3 tick，相机慢一帧在这个平移速度下是 7.5 px，本轮的测量
分辨率是 0.1 px。

## 截图

| 文件 | 内容 |
| --- | --- |
| `screenshots/s4-sheet-still.png` | 35 个静止点，每点 120 × 120 放大 2 倍。行 = 上表 7 种相机，列 = tl / tr / bl / br / c。黄色是命中面那一帧，品红是标记那一帧里的标记像素，由脚本叠在一起；青色十字是脚本按光标坐标画的（游戏不把光标画进帧缓冲） |
| `screenshots/s4-sheet-pan.png` | 12 组平移帧，行 = 上表 4 行，每行依次是 p = 0.25 / 0.5 / 0.75 的「本帧相机、tick 时刻相机」两帧 |
| `screenshots/s4-ortho-p60-d64-h96-br-face.png` | 原始整帧：正交默认形态，右下角的命中面 |
| `screenshots/s4-perspective-p60-d16-br-face.png` | 原始整帧：透视贴近，右下角的命中面 |
| `screenshots/s4-pan-ortho-p60-d64-h48-c-t25-tick.png` | 原始整帧：p = 0.25 时 tick 时刻相机的落点（红）落在中心左侧 17 px |
| `screenshots/s4-ortho-p60-d240-h96-c-face.png` | 原始整帧：正交眼睛拉高到 d240，地形整片看不见（推断是雾），只剩中心的命中面 |

## 对 6b 的要求

- 反投影照上文「取矩阵」一节写，近平面取 NDC z = 1；位置在 `Camera#update` 之后、本帧 extract 之前。
- 6b 的正交相机不要沿用 S2 这种「只在渲染状态里居中」的做法：让 `Projection` 本身产出居中的盒子，或者
  像本轮一样在 `getViewRotationProjectionMatrix` 上补同一个平移。判据是两者对同一个世界点投影出同一
  个像素。
- 正交模式下的选中高亮不要走 vanilla 的线条管线，理由见上文 `VIEW_SHRINK`。
- 单位的渲染插值位置（`stage6a-headless-vs-live.md` 里 `MinecraftWorldView#renderPosition`，现在用
  `getGameTimeDeltaPartialTick(true)`）与相机应当用同一个 partialTick：相机实体被冻结时
  `getCameraEntityPartialTicks` 返回 1，两者会分叉。本轮没有测单位，这一条是读代码得出的。

## 没覆盖到的

- 点中的是方块，不是单位（display 实体）的包围盒；单位拾取是阶段 6b 的 `TC-GAME-047`。
- 只测了 yaw 0 与俯角 60°；30° 俯角与其他 yaw 没跑。
- 光标位置来自 `TestInput`，没有经过真实的 GLFW 光标回调与 HiDPI 缩放（容器里 GUI 缩放按 1920 × 1080
  自动取值）；Retina 屏上 `xpos` 与帧缓冲像素不是 1:1，6b 在真机上还要看一次。

## 复现

```bash
cd <仓库根>
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com"
./gradlew :protocol:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
mkdir -p /tmp/synaxis-s4 && mkfifo /tmp/synaxis-s4/stdin.fifo && ( sleep 86404 > /tmp/synaxis-s4/stdin.fifo & )
./gradlew :server-app:run --no-daemon --console=plain --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.port=25572 < /tmp/synaxis-s4/stdin.fifo > /tmp/synaxis-s4/server.log 2>&1 &
# server.log 出现 "listening on 0.0.0.0:25572" 之后：
SYNAXIS_CAMERA_MODES=ortho CONTAINER=synaxis-mc-client-s4 SYNAXIS_GAMETEST_SERVER=host.docker.internal:25572 \
  OUT=/tmp/synaxis-s4/run client-fabric/gametest-env/run.sh
# run.sh 退出码是 1：JVM 退出时 libopenal 段错误，已知问题，看 report.txt 不看退出码
.venv/bin/python client-fabric/gametest-env/s4_measure.py /tmp/synaxis-s4/run /tmp/synaxis-s4/sheets
echo stop > /tmp/synaxis-s4/stdin.fifo; pkill -f 'sleep 86404'; rm -rf /tmp/synaxis-s4
```

S2 只留一种形态（`SYNAXIS_CAMERA_MODES=ortho`）是为了省 5 秒；S4 的相机形态写死在
`SpikeClientGameTest#screenRay`，不读环境变量。缓存热的情况下整轮客户端 2 分 4 秒，S4 部分截 94 张图。量像素的脚本要 pillow 与 numpy：
`uv pip install --python .venv/bin/python pillow numpy`。
