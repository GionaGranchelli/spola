FROM gradle:8.13-jdk21 AS build

WORKDIR /workspace

# Copy Gradle wrapper for layer caching
COPY gradlew gradlew.bat gradle/ gradle/

# Copy build configuration files only (no source yet)
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/libs.versions.toml gradle/
COPY golem-core/build.gradle.kts golem-core/
COPY golem-cli/build.gradle.kts golem-cli/

# Download and cache dependencies (cached layer - only rebuilds when build config changes)
RUN ./gradlew --no-daemon :golem-cli:dependencies

# Copy source code
COPY golem-core/src golem-core/src
COPY golem-cli/src golem-cli/src

# Build the application
RUN ./gradlew --no-daemon :golem-cli:installDist

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd --system golem && useradd --system --gid golem golem && mkdir -p /golem/.golem /golem/workspace && chown -R golem:golem /golem

WORKDIR /golem
COPY --from=build /workspace/golem-cli/build/install /golem/golem-cli/build/install

EXPOSE 8082 8091

USER golem

CMD ["./golem-cli/build/install/golem-cli/bin/golem-cli", "--help"]
