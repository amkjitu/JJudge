# Build context is the repository root, not this directory:
#   docker build -f arena-api/Dockerfile .
# The multi-module reactor needs the parent pom and arena-common, so the context has to
# span more than the module itself.

# ---------------------------------------------------------------------------------------
# Stage 1 - resolve dependencies. Copying only the poms first means this layer is cached
# until a dependency actually changes; editing Java code no longer re-downloads the world.
# ---------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS deps
WORKDIR /build
COPY pom.xml ./
COPY arena-common/pom.xml arena-common/
COPY arena-api/pom.xml arena-api/
COPY arena-judge/pom.xml arena-judge/
COPY arena-ai/pom.xml arena-ai/
RUN mvn -B -ntp -pl arena-api -am dependency:go-offline

# ---------------------------------------------------------------------------------------
# Stage 2 - compile and package.
# ---------------------------------------------------------------------------------------
FROM deps AS build
COPY arena-common/src arena-common/src
COPY arena-api/src arena-api/src
RUN mvn -B -ntp -pl arena-api -am clean package -DskipTests

# ---------------------------------------------------------------------------------------
# Stage 3 - split the fat jar into Spring Boot layers so that dependency layers stay
# cached across application-only rebuilds.
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /build/arena-api/target/arena-api-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---------------------------------------------------------------------------------------
# Stage 4 - runtime. JRE only, non-root, no build tooling.
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S arena && adduser -S -G arena arena
WORKDIR /app

COPY --from=extract --chown=arena:arena /extract/dependencies/ ./
COPY --from=extract --chown=arena:arena /extract/spring-boot-loader/ ./
COPY --from=extract --chown=arena:arena /extract/snapshot-dependencies/ ./
COPY --from=extract --chown=arena:arena /extract/application/ ./

USER arena
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
