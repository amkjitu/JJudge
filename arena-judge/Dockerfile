# Build context is the repository root, not this directory:
#   docker build -f arena-judge/Dockerfile .
# The multi-module reactor needs the parent pom and arena-common, so the context has to span
# more than the module itself.

# ---------------------------------------------------------------------------------------
# Stage 1 - resolve dependencies. Copying only the poms first means this layer stays cached
# until a dependency actually changes.
# ---------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS deps
WORKDIR /build
COPY pom.xml ./
COPY arena-common/pom.xml arena-common/
COPY arena-api/pom.xml arena-api/
COPY arena-judge/pom.xml arena-judge/
COPY arena-ai/pom.xml arena-ai/
# Best-effort: this only warms the cache, and some optional transitive dependencies are not
# resolvable offline. A genuinely missing dependency still fails the build stage below.
RUN mvn -B -ntp -pl arena-judge -am dependency:go-offline || true

# ---------------------------------------------------------------------------------------
# Stage 2 - compile and package.
# ---------------------------------------------------------------------------------------
FROM deps AS build
COPY arena-common/src arena-common/src
COPY arena-judge/src arena-judge/src
RUN mvn -B -ntp -pl arena-judge -am clean package -DskipTests

# ---------------------------------------------------------------------------------------
# Stage 3 - split the fat jar into Spring Boot layers.
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /build/arena-judge/target/arena-judge-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---------------------------------------------------------------------------------------
# Stage 4 - runtime. JRE only, non-root, no build tooling.
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

# The docker client, for REAL judging mode: the sandbox creates one hardened container per
# submission by invoking it. Only the client - there is no daemon in here; it talks to whichever
# socket is mounted in. In SIMULATED mode nothing ever executes it, and the ~40MB is the price of
# one image that can do both rather than two images to keep in step.
RUN apk add --no-cache docker-cli

RUN addgroup -S arena && adduser -S -G arena arena
WORKDIR /app

COPY --from=extract --chown=arena:arena /extract/dependencies/ ./
COPY --from=extract --chown=arena:arena /extract/spring-boot-loader/ ./
COPY --from=extract --chown=arena:arena /extract/snapshot-dependencies/ ./
COPY --from=extract --chown=arena:arena /extract/application/ ./

USER arena

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# No port and no HTTP health endpoint: this process only consumes Kafka. Compose checks
# liveness by asking the JVM whether it is still running rather than by curling a port that
# does not exist.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
