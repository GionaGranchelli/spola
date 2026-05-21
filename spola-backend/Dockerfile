FROM gradle:8.13-jdk21 AS build

WORKDIR /workspace

# Copy Gradle wrapper for layer caching
COPY gradlew gradlew.bat gradle/ gradle/

# Copy build configuration files only (no source yet)
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/libs.versions.toml gradle/
COPY spola-backend-core/build.gradle.kts spola-backend-core/
COPY spola-backend-cli/build.gradle.kts spola-backend-cli/

# Download and cache dependencies (cached layer - only rebuilds when build config changes)
RUN ./gradlew --no-daemon :spola-backend-cli:dependencies

# Copy source code
COPY spola-backend-core/src spola-backend-core/src
COPY spola-backend-cli/src spola-backend-cli/src

# Build the application
RUN ./gradlew --no-daemon :spola-backend-cli:installDist

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd --system spola && useradd --system --gid spola spola && mkdir -p /spola-backend/.spola /spola-backend/workspace && chown -R spola:spola /spola-backend

WORKDIR /spola-backend
COPY --from=build /workspace/spola-backend-cli/build/install /spola-backend/spola-backend-cli/build/install

EXPOSE 8082 8091

USER spola

CMD ["./spola-backend-cli/build/install/spola/bin/spola", "--help"]
