# Build on Amazon Linux 2023 with Corretto 17, run on the same base without the JDK.
FROM amazoncorretto:17-al2023 AS build
# The AL2023 image ships without tar and gzip, which the Maven wrapper needs to unpack Maven.
RUN dnf install -y tar gzip && dnf clean all
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
# The operator dashboard is built into the jar by the Maven build (pinned Node, npm ci from the lock file).
COPY dashboard dashboard
RUN ./mvnw -q -B package -DskipTests -DskipDashboardTests=true

FROM amazoncorretto:17-al2023-headless
# A fixed non-root user id: the headless image has no useradd, and the app needs no home directory.
USER 10001:10001
WORKDIR /app
COPY --from=build /src/target/folio-pay-bridge-*.jar app.jar
EXPOSE 8081
# Inside a container the app must listen on all interfaces; compose publishes the port on the host's loopback only.
ENV SERVER_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
