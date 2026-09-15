# Synaxis

由 Minestom（Apache-2.0，起点 commit `16842f1954`）硬 fork 而来、独立维护的 Minecraft Java 版服务端引擎。不跟随上游，Minecraft 版本钉死 26.2（协议 776）。不含任何游戏玩法；游戏规则在 M2 起的游戏模块里，引擎禁止依赖游戏。

## 构建

- JDK 25（Homebrew `openjdk@25`）：`export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
- 依赖解析必须带镜像脚本：`./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`（Maven Central 直连不稳，且 Gradle 不读 `http_proxy`）
- Error Prone 开着 `-Werror`：禁止通配导入，未用的 lambda 参数写 `_`
- 冷启动、命令、目录地图、升版本 checklist 见 `docs/ENGINE.md`
- commit message 只描述代码变更，禁止任何 AI 署名或 co-author trailer

## 功能开发纪律
本项目以设计文档 `docs/DESIGN.md` 为单一事实源,以测试文档 `docs/TEST.md` 为验收执行脚本与台账。任何功能新增或改动,必须按此序:先更新设计文档,再改实现代码,再同步测试文档中受影响的用例,并按测试文档执行测试(范围按其回归策略)、在其执行记录中登记轮次。仅改实现代码而未同步两份文档、或未执行并登记测试的改动,视为未完成;设计文档已正确覆盖本次改动(如实现追齐设计的缺陷修复)时,设计文档可不动,在交付说明注明即可。更新设计文档时保持其既有章节结构、ID 约定与 Mermaid 图,状态图与迁移表、图与正文保持一致;更新测试文档时保持用例格式与 TC→REQ 追溯,执行记录只增不改。

## 密钥纪律
运行时密钥只进 `.env`(已 gitignore),仓内只放 `.env.example` 登记「键名 + 用途 + 获取方式」;跨项目共享密钥集中存于 Bitwarden(item 名 = 键名),读取用 `bw get password <键名>` 管道直写、任何输出不回显值;主密码与解锁只由人执行,Agent 不询问主密码、不打印任何密钥值;发现仓内明文密钥立即停下提示轮换(git 历史同样已泄漏)。

## 调研与审计

`docs/research/` 是选型与审计证据链：`00`～`06` 为服务端核心横向调研，`minestom-audit/` 为对起点代码的六路审计、修复去向表与架构评审（第八章是硬 fork 前提下的重排计划）。
