FROM ubuntu:24.04
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git gcc libc6-dev openssh-server tmux python3 xvfb xauth \
    openjdk-17-jdk-headless openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/sshd /sandbox/home /sandbox/tmp \
    && chmod 700 /sandbox /sandbox/home /sandbox/tmp \
    && (printf 'root:'; head -c 32 /dev/urandom | base64) | chpasswd
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV GRADLE_USER_HOME=/root/.gradle
WORKDIR /workspace
COPY . /workspace
RUN cd android && bash gradlew -Pyxi.desktopOnly=true :desktop:testClasses :desktop:cacheIsolatedRuntime \
    -I /workspace/dev/isolated-tests/cache.init.gradle --no-daemon --max-workers=1 \
    -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process
LABEL org.yxi.test-isolation="1"
ENTRYPOINT ["bash", "/workspace/dev/isolated-tests/entrypoint.sh"]
