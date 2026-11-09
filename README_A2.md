# SkiTrak Assignment 2

Assignment 2 is implemented independently of the existing Assignment 1 modules.
`server/`, `client-part1/`, `client-part2/`, and their historical results remain intact.
The root Maven reactor builds only the new Assignment 2 modules.

## 教师澄清已落实

- 保持 10 个雪场和 10 个业务队列；每个雪场串行聚合。
- 默认 `TOP_N=20`。
- 消费线程实验使用 **5 / 10 / 15 / 20**；超过 10 个有效聚合线程时，预期收益趋平。
- 客户端线程仍测试 64 / 128 / 256 / 512。
- 官方 `lift_vertical.json` 已按本次聊天提供的 40 个数值保存到公共资源和部署目录；例如 lift 1=167ft、21=267ft、40=269ft。Pretty print 只改变显示缩进。
- 实例类型由学生选择并记录，不能把不同实例或旧版 A1 的成绩混作同一组实验。

## 本地启动：现在不需要 EC2 或单独安装 Tomcat

需要 Java 11、Maven 和运行中的 Docker Desktop。Tomcat 9、RabbitMQ 和 consumer
由 Docker Compose 启动。首次构建需要从 Maven Central / Docker Hub 下载依赖。

```bash
# macOS 上已有 Corretto 11 时：
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./deployment/build.sh

# 只有 .env 不存在时才复制，避免覆盖已有密码：
test -f deployment/.env || cp deployment/.env.example deployment/.env
# 编辑 deployment/.env，设置本地 RabbitMQ 密码。不要提交该文件。
./deployment/local-up.sh
```

本次协作已生成一个被 Git 忽略、权限为 600 的本地 `.env`，包含随机测试密码。
正常配置使用官方 lift 表，不需要 demo override。

| 地址 | 用途 |
|---|---|
| `http://localhost:8080/health` | ingest 1 就绪状态 |
| `http://localhost:8082/health` | ingest 2 就绪状态 |
| `http://localhost:8081/health` | consumer 就绪状态 |
| `http://localhost:8081/metrics` | 已处理唯一事件、队列、线程、lag、outbox 指标 |
| `http://localhost:15672` | RabbitMQ 管理界面，凭据见本地 `.env` |
| `ws://localhost:8080/leaderboard/1?dayID=1` | 雪场 1 当日榜单 |

接口在 ROOT WAR 下，因此 ALB 的 `/health` 是实际路径，不需要 `/ski-servlet` 前缀。

```bash
python3 monitoring/verify_pipeline.py \
  --ingest-url http://localhost:8080 --ingest-url http://localhost:8082 \
  --consumer-url http://localhost:8081 \
  --lift-file deployment/config/lift_vertical.json --day 363
```

该验证使用尚未写入数据的 day。再次运行请选择另一个空 day，避免混入历史数据。
它发送 100 个唯一刷卡和 20 次跨节点重复请求，独立计算 Top-20，并校验两台服务器上的真实 WebSocket 内容。

小规模客户端运行示例（参数中第 4 项为每个 warmup 线程的事件数）：

```bash
cd client-v2
java -jar target/client-v2.jar http://localhost:8080 5000 64 10 10 buffered 6650 http://localhost:8081 120
```

HTTP 发送完成后客户端继续等待：唯一事件处理完成、队列及在途消息排空、outbox 完成、最终榜单已通过 WebSocket 观察到。

## 模块与主要逻辑

| 模块 | 职责 |
|---|---|
| `common/` | 消息 DTO、官方 lift 表、参数解析、RabbitMQ 拓扑 |
| `server-v2/` | Tomcat ROOT WAR，校验、可靠发布、Channel 池、topic backplane、本机 WebSocket 会话 |
| `consumer/` | 独立进程，有序处理、SQLite 持久化去重/累计值/版本/outbox、Top-N、快照 HTTP API |
| `client-v2/` | 复用 A1 压测逻辑，增加稳定幂等键、真实榜单验证、流水线完成等待 |
| `deployment/` | Docker Compose、独立 EC2 角色安装、systemd、ALB CloudFormation |
| `monitoring/` | 业务正确性、监控采集、队列绘图、ALB 验证、线程与实例数实验脚本 |

### 可靠性边界

1. 客户端每个逻辑 swipe 生成一次 UUID，HTTP 重试和跨 ingest 重试保持 `Idempotency-Key` 不变。
2. ingest 查官方 vertical，借出预建 Channel，持久化发布；处理 mandatory return 和 publisher confirm 后才返回 201。
3. 每个雪场一个 FIFO serial lane；不同雪场在共享线程池中公平轮转。重试发生在原 lane 内，后续消息不能越过失败消息。
4. SQLite WAL + FULL synchronous 的单事务写入 inbox 去重、累计值、版本和 outbox。重启后恢复状态；数据库文件必须保留在持久 EBS/volume 上。
5. consumer 发布变更后的完整榜单到 `leaderboard.topic`，routing key 为 `lb.{resortID}`；确认广播发布后 ack 原始消息。无订阅者是正常通知结果。
6. 每台 WebSocket 服务器有自己的临时队列，只绑定本地有订阅者的雪场；首次订阅先绑定再取权威快照。版本比较、重连恢复及周期快照修复瞬时通知缺失。
7. 队列容量溢出拒绝新发布，产生背压；TTL、死信、失败事件不计作成功聚合。保持 TTL 大于测试及恢复窗口。

数据库不等于高可用集群。本实现允许同一 consumer 在保留数据目录的情况下重启，不支持两台独立 consumer 各自持有不同数据库来随意竞争相同雪场。磁盘永久丢失、超出 TTL、未修复的毒消息都应在报告中明确说明。

## 云端部署与最终作业证据

代码和本地集成完成后才需要 EC2。云端使用独立的 RabbitMQ EC2、consumer EC2，及 1/2/4 台 ingest EC2；ALB 使用至少两个可用区子网。
完整步骤见 [部署说明](deployment/README.md)，指标与测试命令见 [监控说明](monitoring/README.md)。

最终必须实际完成以下实验，不能由本地功能测试代替：

- 1 台 ingest：500K；2 台：500K；4 台：500K，再 1M stress test。
- 客户端线程 64/128/256/512；consumer 配置 5/10/15/20；记录 Top-N、prefetch、ack batch、Channel 池和实例类型。
- HTTP 和端到端吞吐/延迟、失败/重试、RabbitMQ 深度与速率、consumer lag、CPU/内存/网络。
- RabbitMQ 控制台图、ALB 分布及 cookie stickiness、节点故障/重连、真实榜单内容证据。
- 按输入数据对账累计值和最终 Top-N；观察空队列不能单独证明不丢数据。

`AI_USAGE.md` 是基于本次协作事实的反思草稿，需要本人审阅补充后提交。
两页架构 PDF 和本地验证记录与云端性能证据分别保存，避免把未完成的实验写成通过。

- [需求与验证对应表](REQUIREMENTS_A2.md)
- [本地验证结果](results/a2-local/README.md)
- [两页架构 PDF](output/pdf/assignment2-architecture.pdf)
- [架构 Markdown 源文件](ARCHITECTURE_A2.md)
