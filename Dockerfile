FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon completeBuild

FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="RoboCup Rescue Simulation Server"
LABEL org.opencontainers.image.description="RCRS server running on OpenJDK 21"

WORKDIR /opt/rcrs-server

COPY --from=builder /workspace/jars ./jars
COPY --from=builder /workspace/lib ./lib
COPY --from=builder /workspace/maps ./maps
COPY --from=builder /workspace/log4j.properties ./log4j.properties

RUN groupadd --system rcrs \
    && useradd --system --gid rcrs --home-dir /opt/rcrs-server rcrs \
    && mkdir -p scripts logs/log logs/jlog \
    && chown -R rcrs:rcrs logs

USER rcrs

VOLUME ["/opt/rcrs-server/logs"]
EXPOSE 27931

ENTRYPOINT ["java", "-jar", "/opt/rcrs-server/jars/rcrs-server-launcher.jar"]
CMD ["comprun", "--nogui"]
