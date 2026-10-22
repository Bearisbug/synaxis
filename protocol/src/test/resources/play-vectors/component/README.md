# play-vectors/component/

`synaxis:play` 里 `component` 的冻结字节（`docs/DESIGN-PLAY.md` §26 S1 的第一步，`docs/TEST.md` `TC-PLAY-020`、`TC-PLAY-021`）：服务端唯一的编码点 `ComponentBytes`（引擎 `NetworkBuffer.COMPONENT`，原版网络格式 NBT）写出的字节，不带 varint 长度前缀。三个文件对应夹具 `PlayHudFixture` 下发的三个组件：

| 文件 | 组件 |
| --- | --- |
| `title.bin` | 翻译键 `key.category.synaxis.play`，没有参数（场景标题） |
| `hint.bin` | 翻译键 `synaxis.action.cooldown`，一个参数（文本 `1`），颜色 `gold`，fallback `The fallback of synaxis.action.cooldown: %s` |
| `missing.bin` | 翻译键 `synaxis_test.missing`（语言文件里没有），颜色 `green`，fallback `Shown from the fallback` |

服务端 `server-app` 的 `PlayComponentVectorTest` 判 `ComponentBytes` 写出的与这里逐字节相同；客户端模组的 `PlayComponentDecodeTest` 用原版的组件流编解码器解这三个文件，判键、参数、颜色与 fallback。比对失败时，服务端测试的消息里带着 `ComponentBytes` 现在写出的十六进制字节，引擎的编码有意改变时照它重写文件。本目录不进 `play-vectors/` 的 digest，也不绑 `play_version`（见上一级 `manifest.txt`）。
