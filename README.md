# Run tests

```
./gradlew test
```

# Run main.survey.app

```
./gradlew run"
```

# Curl the app
```
curl -v -X POST "localhost:8080/command/blah" -d '{"aggregate_id":"4f188480-9a39-4335-902b-e481af19b3e5"}' -H "accept: application/json" -H "Content-Type: application/json"
```