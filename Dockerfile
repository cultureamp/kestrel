FROM gradle:4.7.0-jdk8-alpine AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build --no-daemon

FROM adoptopenjdk/openjdk11:alpine-jre

ENV APPLICATION_USER ktor
RUN adduser -D -g "" $APPLICATION_USER

RUN mkdir /app
RUN chown -R $APPLICATION_USER /app

USER $APPLICATION_USER

EXPOSE 5005

COPY --from=build /home/gradle/src/build/libs/main-with-deps.jar /app/
COPY --chown=$APPLICATION_USER bin/run-jar-in-container.sh /app/

WORKDIR /app

CMD ["./run-jar-in-container.sh", "main-with-deps.jar"]
