# Kotlin-Eventsourcing

A framework for event-sourced Kotlin apps

## Adding as a dependency

Add our Nexus repository as a Maven source:

* `https://package-repository.continuous-integration.cultureamp.net/repository/maven-snapshots` if you need snapshot builds. 
* `https://package-repository.continuous-integration.cultureamp.net/repository/maven-releases` for just official releases

If you are using Gradle:

```
repositories {
    maven { url 'https://package-repository.continuous-integration.cultureamp.net/repository/maven-snapshots' }
    maven { url 'https://package-repository.continuous-integration.cultureamp.net/repository/maven-releases' }
}
```

then add a dependency such as the following:

```
dependencies {
    implementation "com.cultureamp:kotlin-eventsourcing:0.2.0"
}
```

Making sure the version matches the one that you want.

## Getting Started

See the [sample code](../src/test/kotlin/com/cultureamp/eventsourcing/sample.kt) for an example of how to use this.


# Trello

https://trello.com/b/9mZdY0ZS/kotlin-event-sourcing
