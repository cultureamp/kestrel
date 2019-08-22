# Run tests

```
./gradlew test
```

# Run main.survey.app

```
./gradlew run"
```

# Curl the app
## Create a Thing then Bop it
```shell
curl -v -X POST "localhost:8080/command/survey.thing.CreateThing" -d '{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.thing.Bop" -d '{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}' -H "accept: application/json" -H "Content-Type: application/json"
```
returns `201`:
```json
[
  {"type":"ThingCreated","data":{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}},
  {"type":"Bopped","data":{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}}
]
```

## Create a Survey, Delete then Restore it
```shell
curl -v -X POST "localhost:8080/command/survey.design.Create" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "surveyCaptureLayoutAggregateId": "c6966269-d606-4c48-b157-37f005e4b6f9", "name": {"en": "nameo"}, "accountId": "3a94163b-4dd6-47ae-8eeb-a27d93ecde14", "createdAt": "1995-12-08"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.design.Delete" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "deletedAt": "1995-12-08"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.design.Restore" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "restoredAt": "1995-12-08"}' -H "accept: application/json" -H "Content-Type: application/json"
```
returns `201`:
```json
[
  {"type":"Created","data":{"aggregateId":"4f188480-9a39-4335-902b-e481af19b3e5","name":{"en":"nameo"},"accountId":"3a94163b-4dd6-47ae-8eeb-a27d93ecde14","createdAt":818380800000}},
  {"type":"Deleted","data":{"aggregateId":"4f188480-9a39-4335-902b-e481af19b3e5","deletedAt":818380800000}},
  {"type":"Restored","data":{"aggregateId":"4f188480-9a39-4335-902b-e481af19b3e5","restoredAt":818380800000}}
]
```

# Curl the app with bad data
## Missing field
```shell
curl -v -X POST "localhost:8080/command/survey.design.Create" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "surveyCaptureLayoutAggregateId": "c6966269-d606-4c48-b157-37f005e4b6f9", "name": {"en": "nameo"}, "accountId": "3a94163b-4dd6-47ae-8eeb-a27d93ecde14"}' -H "accept: application/json" -H "Content-Type: application/json"
```
returns `401`
```json
{"field":"createdAt","invalidValue":null}
```

## Invalid data
```shell
curl -v -X POST "localhost:8080/command/survey.design.Create" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "surveyCaptureLayoutAggregateId": "c6966269-d606-4c48-b157-37f005e4b6f9", "namec": {"en": "nameo"}, "accountId": "3a94163b-4dd6-47ae-8eeb-a27d93ecde14", "createdAt": "1995-12-08dfsgsdf"}' -H "accept: application/json" -H "Content-Type: application/json"
```
returns `400`
```json
{"field":"createdAt","invalidValue":"1995-12-08dfsgsdf"}
```


