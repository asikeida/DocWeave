# 云端开发数据库与 DBeaver

## 连接方式

开发 MySQL 部署在选定的个人阿里云实例，代码仍在本机运行。数据库绑定服务器的 127.0.0.1:3306，通过 SSH 隧道访问，不需要向公网开放 3306。

服务器、SSH 用户和私钥位置属于本机或部署环境配置，文档只使用占位值。禁止将真实连接信息、.env 或密码提交 Git。

## 配置文件

- `deploy/cloud-dev/compose.yaml`：MySQL 8.4.13、1GiB 内存上限、256MiB InnoDB 缓存、40 个最大连接，关闭不需要的 X Protocol。
- `deploy/cloud-dev/.env.example`：仅包含数据库名、应用账号和两个密码占位值，部署时复制为服务器的 .env 并替换。
- 本机 `.env.cloud-dev`：私密的本机连接参数，已被 Git 忽略；密码与服务器一致，端口指向本机 SSH 转发端口。应用不会自动读取该文件，需要将其中的应用连接变量填入 IDE 或当前终端。

服务器上将 Compose 与实际 .env 放在受限目录，如 `/opt/docweave-dev/mysql/`。目录权限 700，实际 .env 权限 600。数据存于 named volume，容器停止或重建时保留；禁止在排查时随意执行 `down -v`。

Docker 随服务器启动，MySQL 使用 unless-stopped 重启策略。容器日志按 10MB 轮转、保留 3 份。1GiB 是容器内存上限，非预分配；实际容量仍需根据后续业务观察。

## 本机建立 SSH 隧道

使用已有 OpenSSH 配置中的个人服务器别名：

```text
ssh -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:13307:127.0.0.1:3306 <个人开发服务器别名>
```

这是一条跨平台 OpenSSH 命令，不需要额外项目启动脚本。本机 13307 仅监听 loopback，关闭 SSH 连接会关闭转发；网络中断退出后需要重新连接。端口被占用时应选择新的空闲端口，同时调整应用和 DBeaver 配置。

两个 127.0.0.1 含义不同：第一个是本机监听地址，第二个是 SSH 服务器访问 MySQL 时使用的服务器本机地址。

## DBeaver 如何填写

在本机创建 MySQL 连接：

| 项目 | 设置 |
| --- | --- |
| 驱动 | MySQL |
| 主机 | 127.0.0.1 |
| 端口 | 13307 |
| 数据库 | docweave_dev |
| 用户名 | docweave_dev |
| 密码 | 本机 .env.cloud-dev 的 DOCWEAVE_DB_PASSWORD |
| SSH 页 | 使用上述外部隧道时，不再另开一条内置隧道 |

点击“测试连接”。如果首次缺少 JDBC 驱动，按 DBeaver 提示下载。不要使用 MYSQL root 账号进行日常开发。

若报 `Communications link failure` 且详细信息是 `Connection refused`，先确认本机 13307 的 SSH 隧道仍在运行；它表示 TCP 连接未建立，不等于密码错误。网络中断可能使 SSH 进程退出，恢复网络后需要重新建立隧道。主机明确填写 `127.0.0.1`，避免 localhost 解析到未监听的 IPv6 地址。只有隧道正常后，才继续排查数据库账号或驱动问题。

DBeaver 也支持自己建立 SSH 隧道：SSH 页填写服务器地址、SSH 端口和 SSH 身份；主要页填写数据库主机 `127.0.0.1`、端口 `3306`、数据库名和应用账号密码。此时主要页的地址由 SSH 服务器访问，所以 `127.0.0.1` 指服务器自己，不能填服务器公网地址，也不能填外部隧道端口 `13307`。

DBeaver 内置隧道不自动为 Spring Boot 提供固定转发端口。应用仍可使用独立 OpenSSH 隧道；若 DBeaver 也使用该外部隧道，则按上表配置并关闭其内置 SSH。

## Spring Boot 如何填写

在 IDE 的运行配置设置以下环境变量：

```text
DOCWEAVE_DB_HOST=127.0.0.1
DOCWEAVE_DB_PORT=13307
DOCWEAVE_DB_NAME=docweave_dev
DOCWEAVE_DB_USERNAME=docweave_dev
DOCWEAVE_DB_PASSWORD=<与私密文件一致的应用密码>
```

选择 JDK 21，启动 DocWeaveApplication，默认 local profile。local 表示开发应用的运行方式，不要求数据库物理部署在本机。其他终端启动方式见 [本地开发环境](01-本地开发环境.md)。本机无需运行 Docker 或 MySQL。

开发库与生产库必须使用不同实例或独立的数据库和账号。当前云端实例只用于开发；自动测试仍使用 H2，不连接共享开发库。

## 操作与限制

在服务器部署目录中运行：

```text
docker compose config --quiet
docker compose up -d --wait --wait-timeout 180
docker compose ps
docker compose logs --tail 100 mysql
```

停止数据库使用 `docker compose stop`；不要误删数据卷。已有数据卷初始化完成后，修改 .env 不会自动修改数据库中的账号密码。

若服务器无法直接访问 Docker Hub，可通过可信工作站获取官方镜像并核对内容摘要，传输镜像归档后用 docker image load 导入。该操作只用于部署，不引入本机 Docker Desktop 的日常运行依赖，也不配置未经核验的公共镜像代理。

云端 MySQL 和 SSH 隧道依赖服务器与网络可用性；数据应定期备份。备份恢复与日常备份调度不等于一次连接成功，需要单独验收。

## 本次部署与验证结果

- 仅操作选定的个人开发服务器，安装 Docker Engine 29.9.2 / Compose 5.6.1，未连接公司或腾讯云主机。
- MySQL 8.4.13 容器 healthy；服务器只监听 127.0.0.1:3306，未增加公网数据库规则。
- 容器内存上限 1GiB、缓冲池 256MiB、最大连接 40 均实际核验；初次检查 MySQL 约占 458MiB。
- Docker Hub 直连超时后，按从官方取得的 linux/amd64 manifest SHA256，从 DaoCloud 缓存获取相同镜像；服务器 Descriptor 摘要与官方一致，再标记为 mysql:8.4.13。没有更改 Docker 全局镜像源。
- 本机 OpenSSH 隧道 13307 → 服务器 3306，JDBC 返回 MySQL 8.4.13、docweave_dev 数据库和应用账号；临时表创建、插入、读取、删除成功。
- 本机 Spring Boot 经隧道连接云端数据库并创建 Flyway 历史表，health UP，OpenAPI 可访问；验证进程已停止。
- 写入探针后重建 MySQL 容器，数据仍存在；探针表随后删除。重建后 JDBC 再次登录和读写成功。
- 服务器 backups/ 已生成首份 .sql.gz 备份，权限 600，gzip 完整性检查通过。尚未实施定时备份或恢复演练。
- 本机 Docker Desktop 没有启动；云端数据库的日常使用不依赖它。

当前保留服务器 MySQL 和本机 SSH 隧道。DBeaver 的填写步骤不代表已操作或验证本机 DBeaver 界面。

参考：[DBeaver SSH 配置](https://dbeaver.com/docs/dbeaver/SSH-Configuration/)、[DaoCloud 镜像缓存说明](https://github.com/DaoCloud/public-image-mirror)。
