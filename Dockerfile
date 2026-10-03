# Stage 1: Build Application Jar
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Copy Gradle wrapper and build configuration files first for caching
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle settings.gradle ./

# Download dependencies
RUN ./gradlew dependencies --no-daemon || true

# Copy application source code and build production jar
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Minimal Production Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root system user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Install curl/wget for container health checking
RUN apk --no-cache add curl

# Copy fat JAR from builder stage
COPY --from=builder /workspace/build/libs/*.jar app.jar

# Adjust file permissions
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --retries=3 --start-period=15s \
  CMD curl -f http://localhost:8080/health/live || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
