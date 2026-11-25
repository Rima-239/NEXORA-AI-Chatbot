# ==============================================================================
# NEXORA - Multi-Stage Dockerfile
# Base: Eclipse Temurin OpenJDK 21 (Alpine Linux)
# Zero external build tools (no Maven, no Gradle)
# ==============================================================================

# Stage 1: Build & Compilation
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Java source code
COPY src ./src

# Compile all Java sources into /build/bin using standard javac
RUN mkdir -p /build/bin && \
    find src -name "*.java" > sources.txt && \
    javac -d /build/bin -encoding UTF-8 -Xlint:all @sources.txt

# ==============================================================================
# Stage 2: Production Runtime
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

# Create unprivileged user for security
RUN addgroup -S nexora && adduser -S nexora -G nexora

# Copy compiled classes from builder stage
COPY --from=builder /build/bin /app/bin

# Copy frontend web application and PWA assets
COPY frontend /app/frontend

# Create persistent memory storage directory and set ownership
RUN mkdir -p /app/data/memories && chown -R nexora:nexora /app

USER nexora

# Default environment variables (configurable at container runtime or in Render)
ENV PORT=8080 \
    APP_MODE=PUBLIC \
    FRONTEND_DIR=/app/frontend \
    MEMORY_DIR=/app/data/memories \
    OPENROUTER_BASE_URL=https://openrouter.ai/api/v1 \
    OPENROUTER_DEFAULT_MODEL=openrouter/free \
    RATE_LIMIT_PER_MINUTE=30 \
    MAX_REQUEST_SIZE_BYTES=1048576 \
    REQUEST_TIMEOUT_SECONDS=180

# Expose default HTTP port
EXPOSE 8080

# Health check to ensure zero-downtime container recovery
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:${PORT:-8080}/api/health || exit 1

# Launch NEXORA with Java 21 Container-aware JVM memory controls and Virtual Threads
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/bin", "com.nexora.Main"]
