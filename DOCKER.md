# 改了代码之后怎么用 docker-compose 部署（自建镜像）

## 一、先说结论：为什么必须自己打镜像

`linmax/read:latest` 是别人替你把**官方发行包**打包出来的镜像，里面只有编译好的 `read.jar`，**没有源码**。
你把源码改了，官方那个 jar 不会跟着变，所以只有两条路：

1. **自己编译 + 自己打一个新镜像**（推荐，见第三节）
2. 用 GitHub 的机器编译好推到 ghcr.io，服务器只 `pull`（可选，见第六节）

> ⚠️ **不能只把你编译出来的 `read.jar` 丢进 `./appdata` 覆盖。**
> 我把 `linmax/read` 镜像扒开看了，它的 `/entrypoint.sh` 每次容器启动都会执行：
> ```bash
> cp -rf "$QREAD_DIR"/* "$APP_DIR/"      # /qread/*  →  /app/*
> rm -f "$APP_DIR/conf.yml"
> ```
> 也就是**每次启动都会用镜像里的旧 jar 把你放在数据卷里的 jar 盖回去**。
> 同理 `conf.yml` 每次都会被删掉、按环境变量重新生成——所以**改配置要改 `environment:`，不要去改 `appdata/conf.yml`**（改了下次重启就没了）。

## 二、镜像内部结构（已从镜像里原样复刻）

我从 `linmax/read:latest` 的镜像层里把它的 `Dockerfile` 和 `entrypoint.sh` 取出来了，结构是：

| 路径 | 内容 |
| --- | --- |
| 基础镜像 | `eclipse-temurin:22-jdk`（JDK 22.0.2） |
| `WORKDIR` | `/app` |
| `/qread/` | `COPY . /qread/`，即发行包：`read.jar` + `libs/` + `config/conf-sqlite.yml` + `config/conf-mysql.yml` |
| `/entrypoint.sh` | 入口脚本 |

`entrypoint.sh` 干三件事：

1. 把 `/qread/*` 同步到 `/app`（`/app` 就是挂载卷 `./appdata`）
2. 用环境变量（`DB_TYPE` / `ADMIN_*` / `USER_*` / `SMTP_*` / `SERVER_HTTP_*` / `DISABLE_LOG_TO_FILE` …）把 `config/conf-*.yml` 渲染成 `/app/conf.yml`
3. 执行 `$JAVA_CMD`（默认 `java -jar /app/read.jar`）

**本仓库新增的 `Dockerfile` 完全按这套约定来**，所以你原来的 `environment:` 段、`./appdata` 数据卷都能原样复用，只需要把 `image:` 换成 `build:`。

## 三、部署步骤

### 新增的文件

| 文件 | 作用 |
| --- | --- |
| `Dockerfile` | 两阶段构建：用 `gradle:8.10-jdk22` 编译出 `read.jar` + `libs/`，再用 `eclipse-temurin:22-jdk` 打包运行镜像 |
| `docker/entrypoint.sh` | 从 `linmax/read` 镜像里原样取出，保证环境变量行为 100% 一致 |
| `docker/config/conf-sqlite.yml` | SQLite 配置模板 |
| `docker/config/conf-mysql.yml` | MySQL 配置模板 |
| `docker-compose.yml` | 和你原来那份一样，只是把 `image: linmax/read:latest` 换成了 `build: .` |
| `.dockerignore` | 构建上下文瘦身 |
| `.github/workflows/docker.yml` | 可选：GitHub Actions 自动出镜像（不用可以删） |

### 在服务器上执行

```bash
cd /root/read          # 你放 read 仓库的目录（有 docker-compose.yml 的那层）
git pull               # 拉到包含 Dockerfile 的新代码

docker compose down    # 旧容器也叫 qread，不停掉新容器会重名冲突

docker compose up -d --build     # 编译 + 打镜像 + 启动

docker compose logs -f           # 看日志
```

首次 `--build` 会：拉 `gradle:8.10-jdk22` 和 `eclipse-temurin:22-jdk` 两个基础镜像 → 下载 Maven 依赖 → 编译 → 打包。
**大概 5～15 分钟**，看网络情况；之后再次 build 有层缓存，会快很多。

### 验证

```bash
docker compose ps
docker compose logs -f --tail=100     # 启动完成会看到 solon 的启动 banner
docker compose exec qread ls -la /app # 应能看到 read.db / storage / conf.yml / logs
```

- 浏览器打开 `https://reader.4678553.xyz`，能正常进后台/书城即成功。
- 本次改动新增了 `browsing_history` / `search_history` 两张表，由 `@AutoTable` 在启动时自动建表，
  启动日志里会有建表相关输出；也可以看 `/app/read.db` 的修改时间/体积变化。

### 你自己那份 `environment:` 怎么办

把你原来 `docker-compose.yml` 里 `environment:` 整段**原样复制**到新文件里（尤其是 `ADMIN_USERNAME` / `ADMIN_PASSWORD` / `JAVA_CMD`）。
仓库里那份 `docker-compose.yml` 填的是模板默认值，只是个参考，别把管理员密码又改回 `adminadmin`。

## 四、编译内存不够怎么办

`Dockerfile` 里给 Gradle 抬到了 `-Xmx1g`：

```dockerfile
RUN printf '\norg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8\n' >> /src/gradle.properties
```

- 服务器内存 < 2G：把它调到 `-Xmx768m`，并关掉其他占内存的服务再 build。
- 编译报 `OutOfMemoryError`：调到 `-Xmx2g` 重试。

## 五、数据与备份

`./appdata` 就是全部数据：`read.db`（SQLite 库）、`storage/`（书源/书/缓存/头像等）、`logs/`、`bookcache/`。
**换镜像不会动它**，备份只要备份这一个目录：

```bash
tar czf appdata-$(date +%F).tar.gz appdata
```

> 注：`appdata/` 里可能还留着旧镜像写进去的 `read.jar`、`libs/`、`config/`。
> 它们每次启动都会被覆盖成新镜像里的版本，不会造成问题，也不会影响数据。

## 六、方案二：不在服务器编译（GitHub Actions → ghcr.io）

仓库已经加了 `.github/workflows/docker.yml`。推到 GitHub 后：

1. Actions 里会自动构建镜像并推到 `ghcr.io/<你的用户名>/read:latest`
2. 去 GitHub → 你的 Profile → Packages，把该 package 可见性改成 **Public**
   （否则服务器拉取前要先 `docker login ghcr.io`）
3. 服务器上把 compose 的 `build:` 段换成：
   ```yaml
   image: ghcr.io/<你的用户名>/read:latest
   ```
   然后 `docker compose pull && docker compose up -d`

好处是服务器不需要 JDK/Gradle、也不需要下载几百兆依赖。

## 七、方案三（应急）：不改镜像，只换 jar

如果只是临时验证、不想 build 镜像：把你自己编译出的 `read.jar` 放到 `./appdata/`，然后覆盖 entrypoint，
让卷里的 jar 优先（**注意别用镜像自带的 entrypoint，它会把你覆盖掉**）：

```yaml
services:
  qread:
    image: linmax/read:latest
    container_name: qread
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./appdata:/app
    environment:
      TZ: Asia/Shanghai
      JAVA_CMD: java -jar /app/read.jar
    # ↓ 覆盖入口：先让卷里的 read.jar 顶掉镜像内的旧 jar，再同步、再启动
    entrypoint: ["/bin/sh", "-lc"]
    command:
      - "cp -f /app/read.jar /qread/read.jar && cp -rf /qread/* /app/ && java -jar /app/read.jar"
```

限制：

- 依赖必须和镜像里的 `libs/` 兼容。本次改动只新增了实体类、Mapper、Controller，用的都是已有依赖，所以可行；
  以后**一旦新增了第三方依赖，这条路就不行了**，必须走方案一。
- 走这条路时 `entrypoint` 被覆盖，`conf.yml` 不会再被重新生成，会沿用 `appdata/` 里已有的那份，
  **改 `environment:` 不再生效**。

## 八、常见坑

- **nginx 反代必须支持 websocket**，涉及 `/api/版本号/ws`、`/debug`、`/rssdebug`、`/checkdebug` 四个路径。
- **SVG 中文变方框**：容器里装中文字体
  ```bash
  sudo apt install fonts-wqy-microhei fonts-wqy-zenhei xfonts-wqy
  ```
- **端口**：容器内是 8080。要换端口改 `JAVA_CMD: java -Dserver.port=9090 -jar /app/read.jar`，或改 compose 的端口映射。
- **Docker Hub 拉不动**：给 daemon 配镜像加速（`/etc/docker/daemon.json`）。
  Maven 依赖这边 `build.gradle.kts` 已经配了腾讯云镜像，一般没问题。
- **镜像里没有 `sqlite3` 命令**，想在容器里查库可以先把 `read.db` 拷出来在本地用工具看。
