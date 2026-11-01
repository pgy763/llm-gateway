# LLM Gateway · 大模型统一接入与任务编排网关

[![ci](https://github.com/pgy763/llm-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/pgy763/llm-gateway/actions/workflows/ci.yml)

一个基于 Spring Boot 3 + Java 21 的服务。它要解决的问题是：

> **当一个公司有多个系统都要用 AI 时，怎么把这些调用管起来，而不是各调各的。**
>
> 这组内容里的位置：**[接入手册](https://pgy763.github.io/hello-ai-backend/) 讲单个系统怎么接**、**[踩坑手册](https://pgy763.github.io/llm-dev-pitfalls/all) 记接的时候会踩什么**、**这里是接完之后「多系统共用」的那一层**。总入口：[pgy763.github.io](https://pgy763.github.io/)

---

## 一、这个项目解决什么实际问题

假设你在给一家公司写后端，公司有 5 个系统想用 AI：

- 客服系统想自动回复客户问题
- 运营系统想批量生成商品文案
- 数据系统想把报表数据转成人能看懂的话
- 内部工具想自动写周报摘要
- 另外还有一个实验性的功能在试

最直觉的做法是：每个系统自己申请一个 API Key，自己写 HTTP 去调。**但这么干很快就会踩到五个坑。**

| 坑 | 具体是什么样子 |
| --- | --- |
| **密钥满天飞** | 5 个系统的代码、配置、Git 提交历史里全是密钥。谁离职了要换 Key，得翻 5 个仓库 |
| **挂一家全挂** | DeepSeek 一宕机，5 个功能同时不可用。修复方式只能是等它恢复 |
| **花了多少不知道** | 月底账单出来才发现超支，但完全不知道是哪个系统花的 |
| **被刷了才发现** | 某个接口忘了限流，被人写脚本狂刷，一晚上烧掉一个月预算 |
| **长任务会丢** | 用户提交"生成一份报告"，跑到一半服务器重启，这个任务就永远卡住了 |

这个项目把这五个坑全部填上。

---

## 二、它是怎么填的

一句话概括：**所有系统只认这一个地址，剩下的事情它管。**

```
   客服系统 ─┐
   运营系统 ─┤
   数据系统 ─┼──→  【 LLM Gateway 】  ──→  DeepSeek
   内部工具 ─┤      鉴权 · 限流 · 路由          Kimi
   实验功能 ─┘      降级 · 记账 · 编排          （挂了自动换）
```

对应到五个坑：

| 原来的坑 | 网关的做法 |
| --- | --- |
| 密钥满天飞 | 业务系统只拿**虚拟密钥**，真实密钥只存在网关一处 |
| 挂一家全挂 | 配一条**降级链**：DeepSeek → Kimi → 兜底模型，自动切换 |
| 花了多少不知道 | 每次调用都**记账**，按密钥统计 token 用量 |
| 被刷了才发现 | **令牌桶限流**，按密钥维度限速，刷爆自己的桶不影响别人 |
| 长任务会丢 | 多步任务用**状态机**管理，状态存数据库，重启后能接着跑 |

---

## 三、两套接口，对应两种用法

### 用法一：普通问答（`POST /v1/chat`）

一问一答，秒级返回。适合"客服自动回复"这类场景。

```bash
curl -X POST http://localhost:8080/v1/chat \
     -H "X-Api-Key: sk-demo-0001" \
     -H "Content-Type: application/json" \
     -d '{"messages":[{"role":"user","content":"用一句话解释什么是限流"}]}'
```

返回：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "requestId": "req-3f8a1c9d2e4b7a60",
    "content": "限流就是……",
    "provider": "deepseek",
    "fallbackUsed": false,
    "latencyMs": 830,
    "usage": { "promptTokens": 18, "completionTokens": 96, "totalTokens": 114 }
  }
}
```

注意 `provider` 字段：**调用方从来不需要指定用哪家模型**，网关自己决定。它还会告诉你这次用的哪家、有没有走降级、花了多少 token。

### 用法二：多步任务（`POST /v1/tasks`）

提交一个"活"，后台慢慢做，随时可查进度。适合"生成一份报告"这类场景。

```bash
# 提交任务，立刻拿到任务号
curl -X POST http://localhost:8080/v1/tasks \
     -H "X-Api-Key: sk-demo-0001" \
     -H "Content-Type: application/json" \
     -d '{"goal":"写一篇 800 字的文章，介绍令牌桶限流算法"}'

# 查进度
curl http://localhost:8080/v1/tasks/task-xxxxxxxx

# 看它经历了哪些状态变化
curl http://localhost:8080/v1/tasks/task-xxxxxxxx/history
```

任务会经历这样的流程：

```
你的任务: 写一篇文章
        ↓
   GENERATING  生成中   ← 调模型写初稿
        ↓
   REVIEWING   审查中   ← 检查字数、内容是否合格
        ↓
   合格吗？
     ├─ 不合格 → 回到 GENERATING 重写（最多 2 次）
     └─ 合格   ↓
   FINALIZING  定稿中
        ↓
   COMPLETED   完成
```

**这套流程不是写在代码里的 if-else，而是一张明确定义的状态表。** 每一步都记在数据库里，所以：

- 服务器在"审查中"重启 → 重启后从数据库读到"它停在审查中"，接着跑
- 有人想给一个"已完成"的任务发"重写"指令 → 直接被拒绝，并告出为什么

---

## 四、五分钟跑起来

### 你需要准备什么

- **JDK 21**（`java -version` 能看到 21 就行）
- **Docker**（用来起数据库，不想装也可以，见下面说明）
- ~~Maven~~ **不用单独装** —— 项目自带 Maven Wrapper（`mvnw`），首次运行会自动下载 Maven

### 第 1 步：启动数据库

```bash
docker compose up -d
```

这会启动两个容器：PostgreSQL（存数据）和 Redis（做限流和健康状态）。

> **没有 Docker 怎么办？** 自己装一个 PostgreSQL 16 和 Redis 7 也行，
> 或者临时把 `application.yml` 里的 `spring.datasource` 换成 H2 内存库。

### 第 2 步：启动应用

```bash
./mvnw spring-boot:run
# 或者用你本地的 maven：mvn spring-boot:run
```

看到 `Started GatewayApplication in x.xxx seconds` 就成功了。

**不需要申请任何 API Key 也能跑通全流程** —— 项目内置了一个模拟提供方（`mock`），
它会用固定的假数据回答你。等你申请到真密钥，改一下配置就能自动切换。

### 第 3 步：验证一下

```bash
curl -X POST http://localhost:8080/v1/chat \
     -H "X-Api-Key: sk-demo-0001" \
     -H "Content-Type: application/json" \
     -d '{"messages":[{"role":"user","content":"你好"}]}'
```

应该能看到一段模拟回答。

### 第 4 步：看看流式效果

```bash
curl -N -X POST http://localhost:8080/v1/chat/stream \
     -H "X-Api-Key: sk-demo-0001" \
     -H "Content-Type: application/json" \
     -d '{"messages":[{"role":"user","content":"讲个笑话"}],"stream":true}'
```

`-N` 参数让 curl 不做缓冲，你就能看到文字一段一段往外冒的"打字机"效果。

### 第 5 步：看看降级是怎么发生的

项目内置了一个专门用来制造故障的模型 `mock-flaky`，它**前两次调用必定失败**。
拿它去请求，你会看到网关自动切换到了下一家：

```bash
curl -X POST http://localhost:8080/v1/chat \
     -H "X-Api-Key: sk-demo-0001" \
     -H "Content-Type: application/json" \
     -d '{"model":"mock-flaky","messages":[{"role":"user","content":"测试降级"}]}'
```

响应里的 `fallbackUsed` 会是 `true`，说明它确实换了提供方才成功。

### 第 6 步：接一个真实的大模型

以 DeepSeek 为例：

```bash
export DEEPSEEK_API_KEY=你的密钥
./mvnw spring-boot:run
```

因为配置里 DeepSeek 的优先级是 1（最优先），mock 是 99（最后兜底），
所以配好密钥后它会自动优先使用真实模型，不需要改任何代码。

---

## 五、项目结构

```
llm-gateway/
├── README.md                      你正在看的这份
├── pom.xml                        依赖配置
├── docker-compose.yml             一键起 PostgreSQL + Redis
├── docs/                          详细设计文档（建议按顺序读）
│   ├── 01-项目背景与要解决的问题.md
│   ├── 02-架构与一次请求的完整旅程.md
│   ├── 03-状态机设计详解.md
│   ├── 04-降级熔断与限流.md
│   ├── 05-断点续跑与并发控制.md
│   └── 06-扩展指南与面试问答.md
└── src/main/java/com/pgy/gateway/
    ├── api/                       对外接口（HTTP 层）
    │   ├── ChatController.java        对话接口
    │   ├── TaskController.java        任务接口
    │   ├── AdminController.java       管理接口
    │   └── dto/                       返回给调用方的对象
    │
    ├── application/               应用层：把各个模块编排起来
    │   ├── ChatService.java           对话主流程
    │   ├── TaskService.java           任务状态机推进
    │   ├── ApiKeyService.java         密钥鉴权与管理
    │   ├── MeteringService.java       记账
    │   └── StatsService.java          统计指标
    │
    ├── domain/                    领域层：纯业务规则，不碰数据库
    │   ├── chat/                      统一的消息/请求/响应格式
    │   ├── provider/                  各厂商的适配器
    │   ├── route/                     选路与降级链
    │   └── task/                      状态机 ★ 核心
    │
    ├── infrastructure/            基础设施层：数据库、缓存、限流实现
    │   ├── persistence/               数据库实体与访问接口
    │   ├── ratelimit/                 令牌桶限流
    │   └── health/                    提供方健康状态
    │
    └── config/                    配置类
```

> **为什么要分这么多层？**
> 简单说：`domain` 那一层完全不知道数据库和网络的存在，所以它的规则可以被单独测试、单独复用。
> 详细解释见 [docs/02-架构与一次请求的完整旅程.md](docs/02-架构与一次请求的完整旅程.md)。

---

## 六、接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/chat` | 普通问答 |
| POST | `/v1/chat/stream` | 流式问答（SSE） |
| POST | `/v1/tasks` | 提交多步任务 |
| GET | `/v1/tasks/{taskId}` | 查任务状态 |
| GET | `/v1/tasks` | 任务列表 |
| GET | `/v1/tasks/{taskId}/history` | 任务的状态流转轨迹 |
| POST | `/v1/tasks/{taskId}/cancel` | 取消任务 |
| GET | `/admin/keys` | 密钥列表与用量 |
| POST | `/admin/keys?name=xxx&quota=1000000` | 新建密钥 |
| POST | `/admin/keys/{id}/enabled?enabled=false` | 启用/禁用密钥 |
| POST | `/admin/keys/{id}/reset` | 清空已用量 |
| GET | `/admin/stats` | 近 24 小时运行指标 |
| GET | `/admin/logs` | 最近 50 条调用日志 |

所有接口都通过请求头 `X-Api-Key` 携带虚拟密钥。

---

## 七、几个关键设计，以及为什么这么设计

这一节是全项目最值钱的部分。**代码只是实现，这些取舍才是真正要讲的东西。**

### 1. 为什么要有"适配器"这一层

因为每家模型厂商的接口细节都不一样。直接调的话，业务代码里会散落各种 if-else：

```java
if ("deepseek".equals(vendor)) { ... } 
else if ("kimi".equals(vendor)) { ... }
```

新增一家就要改一遍业务代码，还得回归测试所有调用点。

用适配器之后，新增厂商只需要**加一个实现类**，业务代码一行不动。

验证方法：把配置里的 `provider: deepseek` 改成别的，业务代码改了几行？答案是零行。

### 2. 降级链：什么时候该重试，什么时候不该

这是个很容易做错的点。如果无脑重试，会浪费三家额度还得不到结果。

网关的判断依据是错误类型：

| 上游返回 | 判断 | 原因 |
| --- | --- | --- |
| 超时 | 换下一家 | 这家可能只是临时抽风 |
| 429 限流 | 换下一家 | 换一家立刻就能成 |
| 5xx 服务错误 | 换下一家 | 是它自己的问题 |
| 400 参数错误 | **直接失败** | 换一家也是一样的错误 |
| 401 密钥无效 | **直接失败** | 换一家也是一样的错误 |

### 3. 熔断：别让用户陪着一个挂掉的模型等超时

如果 DeepSeek 挂了，而每次请求都要等它超时 60 秒才切换，
用户就得干等一分钟——这比直接失败还难受。

所以网关会记住"连续失败次数"。超过阈值（默认 3 次）就把它暂时拉黑，
30 秒内直接跳过它，让请求秒切到备用模型。

### 4. 流式模式下，降级有个绕不过去的限制

这是实现中一个必须想清楚的问题：

如果已经往客户端吐了半段文字，此时上游断了，**能不能换个模型接着吐？**

**不能。** 因为两个模型写的上下文接不上，用户会看到前后矛盾的两段内容。
所以代码里专门记了一个 `alreadyEmitted` 标志：一旦输出过内容，就只能如实报错终止。

代码位置：`ChatService.streamWithFallback()`

### 5. 令牌桶：为什么不用简单的计数器

"每分钟最多 60 次"这种计数器有个漏洞：
用户可以在 12:00:59 打满 60 次，12:01:00 又打满 60 次 —— 两秒内灌进来 120 次请求。

令牌桶没有这个问题：令牌按固定速率慢慢滴进桶里，桶满了就不再积攒。
这样既限制住了长期速率，又允许短时间的正常突发。

### 6. 状态机：为什么不用一个字符串字段到处 if

如果就写 `if ("处理中".equals(status)) status = "完成"`，时间一长必然出现这种脏数据：

- 一个任务既"已完成"又"正在重写"
- 从"已取消"里又跳回了"生成中"

状态机的做法是把所有合法的"从哪来 → 到哪去"集中写进一张表。
表上没有的迁移，代码会直接抛异常拒绝。

好处是：**"不可能的状态"从源头上就产生不了。**

代码位置：`TaskStateMachine.java` 的 `TRANSITIONS` 表，一眼能看完所有合法路径。

### 7. 断点续跑：状态存数据库，谁来跑都行

任务状态是持久化的，配合一个定时补偿扫描：
每隔 2 秒找出"超过 30 秒没有动静"的任务，重新推进。

所以服务器重启不会丢任务 —— 重启后扫描会把它捡起来接着跑。

### 8. 一个刻意的取舍：Redis 挂了不能让网关跟着挂

限流和健康状态都放在 Redis 里，但代码里每个 Redis 调用都包了 try-catch，
失败时按"放行 / 健康"处理（术语叫 fail-open）。

理由：这两个功能都只是**保护措施**。如果它们反过来成了单点故障，
那本末倒置了。宁可短暂失去限流能力，也不能让整个网关不可用。

---

## 八、进一步阅读

代码里的注释写得比较密，是按"能直接看懂"的标准写的。配合下面这些文档看效果更好：

| 文档 | 内容 |
| --- | --- |
| [01-项目背景与要解决的问题](docs/01-项目背景与要解决的问题.md) | 从零讲清楚为什么需要网关，每个坑配一个真实场景 |
| [02-架构与一次请求的完整旅程](docs/02-架构与一次请求的完整旅程.md) | 分层怎么分、为什么这么分，一个请求进来后经过了哪些代码 |
| [03-状态机设计详解](docs/03-状态机设计详解.md) | 状态、事件、迁移表到底在说什么，为什么这么做 |
| [04-降级熔断与限流](docs/04-降级熔断与限流.md) | 降级链、熔断、令牌桶的完整原理与实现细节 |
| [05-断点续跑与并发控制](docs/05-断点续跑与并发控制.md) | 任务重启后怎么恢复，并发下怎么防止重复执行 |
| [06-扩展指南与面试问答](docs/06-扩展指南与面试问答.md) | 怎么加新厂商、加新状态，以及面试常问的问题怎么答 |

---

## 九、已知的简化处理

这是一个用来学习和展示的项目，为了保持可读性，有几处刻意做了简化。
**了解这些反而比假装没有更有价值** —— 面试时被问到也能坦然回答。

| 简化点 | 真实项目应该怎么做 |
| --- | --- |
| 虚拟密钥明文存储 | 只存哈希值，创建时返回一次明文 |
| 管理接口没有鉴权 | 加管理员角色校验 |
| 补偿扫描用"时间阈值"判断任务是否卡住 | 用分布式锁或任务租约，避免重复执行 |
| 单机部署，线程池是本地线程池 | 用消息队列解耦，任务分发到多台机器 |
| 审查用的是简单规则 | 再接一个模型做打分（Critic 模式） |
| 没有做分布式链路追踪 | 接入 OpenTelemetry，用 requestId 串起全链路 |

---

## 十、环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 21+ |
| Spring Boot | 3.3.5 |
| PostgreSQL | 16（用了 `jsonb` 之外的基本类型，15+ 都能跑） |
| Redis | 7 |

---

## License

MIT
