# The IdeaNest API, as an image — docs/architecture.md §19, issue #139.
#
# Two stages. The build stage carries a JDK, Gradle's caches and the whole source
# tree; the runtime stage carries a JRE and one jar. Shipping the build stage
# would put a compiler, the test sources and every dependency's source jar into
# production — a larger attack surface and a slower pull, for nothing.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
# Pinned to a digest-less tag on purpose: the wrapper verifies its own Gradle
# distribution's checksum, and the JDK here only has to be 21 or newer. A digest
# would have to be bumped by hand for every base-image security release, which is
# the change nobody makes.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The wrapper and the build files first, and the sources after. Docker caches by
# layer, so a change to a Java file must not invalidate the dependency download —
# which is most of the build's wall-clock time.
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src src

# `-x test`, and the reason is not speed. The tests need Docker — Testcontainers
# starts a real PostgreSQL — and a build that runs them inside a container needs
# a socket mounted into it. CI has already run them against the same commit; a
# second run here would be the same assertions with a worse failure mode.
RUN ./gradlew --no-daemon clean bootJar -x test

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# A user of its own. The base image runs as root, and a process that never needs
# to write outside /tmp has no reason to be able to.
RUN groupadd --system ideanest && useradd --system --gid ideanest --home /app ideanest

WORKDIR /app
COPY --from=build --chown=ideanest:ideanest /build/build/libs/*.jar app.jar

USER ideanest

EXPOSE 8080

# `MaxRAMPercentage` and not `-Xmx`. A container's memory limit is set by the
# orchestrator and changes per environment; a fixed heap is either wasteful in
# the large one or the reason the small one is killed. Seventy-five per cent
# leaves room for metaspace, thread stacks and the direct buffers Netty and the
# JDBC driver take outside the heap.
#
# `ExitOnOutOfMemoryError`, because a JVM that has run out of heap is a process
# the orchestrator should replace rather than one that limps: it will not recover,
# and it will fail health checks in ways that read as a database problem.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The orchestrator's readiness probe is the real one — it can remove an instance
# from the load balancer, which this cannot. This exists for `docker run` and for
# a compose stack, where nothing else is watching.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# `exec` form through a shell, so JAVA_OPTS is expanded and the JVM is still
# PID 1 — which is what makes SIGTERM reach it and the graceful shutdown in
# `application.yml` actually run.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
