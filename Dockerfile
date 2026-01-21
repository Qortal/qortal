FROM maven:3-openjdk-11 as builder


WORKDIR /work
COPY . .
RUN mvn clean package

###
FROM openjdk:11

RUN useradd -r -u 1000 -g users qortal && \
    mkdir  /qortal && \
    chown 1000:100 /qortal

COPY --from=builder /work/log4j2.properties /qortal/
COPY --from=builder /work/target/qortal*.jar /qortal/qortal.jar
COPY --from=builder /work/start.sh /qortal/
COPY --from=builder /work/settings.json /qortal/
USER 1000:100

EXPOSE 12391 12392
HEALTHCHECK --start-period=5m CMD curl -sf http://127.0.0.1:12391/admin/info || exit 1

WORKDIR /qortal
VOLUME /qortal

## ENTRYPOINT ["java"]
## CMD ["-Djava.net.preferIPv4Stack=false", "-jar", "/usr/local/qortal/qortal.jar"]

ENTRYPOINT ["/qortal/start.sh"]
CMD ["docker"]



