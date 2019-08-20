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
curl -v -X POST "localhost:8080/command/survey.thing.CreateThing" -d '{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.thing.Bop" -d '{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}' -H "accept: application/json" -H "Content-Type: application/json"

curl -v -X POST "localhost:8080/command/survey.design.Create" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "surveyCaptureLayoutAggregateId": "c6966269-d606-4c48-b157-37f005e4b6f9", "name": {"en": "nameo"}, "accountId": "3a94163b-4dd6-47ae-8eeb-a27d93ecde14", "createdAt": "1995-12-08T1230Z"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.design.Delete" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "deletedAt": "1995-12-08T1230Z"}' -H "accept: application/json" -H "Content-Type: application/json"
```