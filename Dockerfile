# syntax=docker/dockerfile:1
#
# 自建轻阅读（read）后端镜像
#
# 为什么需要这个文件：
#   linmax/read:latest 是别人用官方发行包（read.jar + libs/ + config/）打包出来的镜像，
#   里面并没有源码。你改了代码，必须自己重新编译并重新打包成新镜像。
#
# 这个 Dockerfile 与 linmax/read 的目录约定完全一致，所以
#   - 你原来的 docker-compose.yml 里所有环境变量继续生效
#   - 你原来的 ./appdata 数据卷继续可用
#   只需要把 image: linmax/read:latest 换成 build: . 即可。

# ============================================================
# 阶段 1：编译。产出 build/libs/solon-read-1.0-SNAPSHOT.jar 与 libs/（依赖包）
# ============================================================
FROM gradle:8.10-jdk22 AS build

# 用 root 编译，避免 COPY 进来的文件属主不是 gradle 用户导致无法写 build/、libs/
USER root
WORKDIR /src

COPY . /src

# 项目本身没配 org.gradle.jvmargs（默认 512m），Kotlin 编译容易 OOM，这里抬高一点。
# 如果服务器内存紧张（<2G），可以把 1g 调小；如果仍然 OOM，调到 2g。
RUN printf '\norg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8\n' >> /src/gradle.properties

# jar 任务依赖 copyDependencies，会把 runtimeClasspath 全部拷到 /src/libs/
RUN gradle jar --no-daemon --console=plain \
 && test -f /src/build/libs/solon-read-1.0-SNAPSHOT.jar \
 && test -d /src/libs

# ============================================================
# 阶段 2：运行。目录布局刻意与 linmax/read 保持一致
#   /qread/read.jar   主程序（manifest 里 Class-Path 指向 libs/xxx.jar）
#   /qread/libs/      全部依赖 jar —— 缺了它 jar 起不来
#   /qread/config/    conf.yml 模板
#   /entrypoint.sh    用环境变量生成 /app/conf.yml 并启动
#   /app              工作目录，挂载成数据卷（read.db / storage / logs / bookcache）
# ============================================================
FROM eclipse-temurin:22-jdk

WORKDIR /app

COPY --from=build /src/build/libs/solon-read-1.0-SNAPSHOT.jar /qread/read.jar
COPY --from=build /src/libs /qread/libs
COPY docker/config /qread/config
COPY docker/entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
