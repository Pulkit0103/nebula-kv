FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/nebula-kv-0.1.0-SNAPSHOT.jar nebula-kv.jar
EXPOSE 7777 7778
ENTRYPOINT ["java", "-jar", "nebula-kv.jar"]
