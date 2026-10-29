# ---------- build stage ----------
FROM maven:3.10-eclipse-temurin-27 AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies first (for Docker layer caching)
RUN mvn dependency:resolve -B

COPY src ./src
RUN mvn package -DskipTests -B

# ---------- runtime stage ----------
FROM eclipse-temurin:27-jre

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

RUN useradd --system --no-create-home --shell /usr/sbin/nologin appuser
USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
