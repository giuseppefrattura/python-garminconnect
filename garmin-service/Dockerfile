# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies first (for Docker layer caching)
RUN mvn dependency:resolve -B

COPY src ./src
RUN mvn package -DskipTests -B

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

RUN adduser --disabled-password --no-create-home appuser
USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
