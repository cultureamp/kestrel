# Run tests

```
./gradlew test
```

# Run main.survey.app

```
./gradlew run"
```

# Run with Docker
```
docker build .
docker run -p 8080:8080 <id from above>
```

# Curl the app
## Create a Thing, Bop it then Explode it
```shell
curl -v -X POST "localhost:8080/command/survey.thing.CreateThing" -d '{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.thing.Bop" -d '{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.thing.Explode" -d '{"aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248"}' -H "accept: application/json" -H "Content-Type: application/json"
```
returns `201`:
```json
{
  "aggregateId":"939c2703-673d-4d44-aee5-0b61f9b35248",
  "events":[
    {"type":"ThingCreated","data":{}},
    {"type":"Bopped","data":{}}
  ]
}
```
then `403` because `Explode` yields an error

## Create a Survey, Delete then Restore it
```shell
curl -v -X POST "localhost:8080/command/survey.design.Create" -d '{"aggregateId": "2b8d5eef-622f-4aef-97ae-977217be4831", "surveyAggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "surveyCaptureLayoutAggregateId": "c6966269-d606-4c48-b157-37f005e4b6f9", "name": {"en": "nameo"}, "accountId": "3a94163b-4dd6-47ae-8eeb-a27d93ecde14", "createdAt": "1995-12-08"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.design.Delete" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "deletedAt": "1995-12-08"}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.design.Restore" -d '{"aggregateId": "4f188480-9a39-4335-902b-e481af19b3e5", "restoredAt": "1995-12-08"}' -H "accept: application/json" -H "Content-Type: application/json"
```
returns `201`:
```json
{
  "aggregateId":"2b8d5eef-622f-4aef-97ae-977217be4831",
  "events":[
    {"type":"SurveySagaStarted","data":{"surveyAggregateId":"4f188480-9a39-4335-902b-e481af19b3e5","surveyCaptureLayoutAggregateId":"c6966269-d606-4c48-b157-37f005e4b6f9","name":{"en":"nameo"},"accountId":"3a94163b-4dd6-47ae-8eeb-a27d93ecde14","createdAt":818380800000,"startedAt":1577401722684}},
    {"type":"StartedCreatingSurvey","data":{"command":{"aggregateId":"4f188480-9a39-4335-902b-e481af19b3e5","surveyCaptureLayoutAggregateId":"c6966269-d606-4c48-b157-37f005e4b6f9","name":{"en":"nameo"},"accountId":"3a94163b-4dd6-47ae-8eeb-a27d93ecde14","createdAt":818380800000},"startedAt":1577401722684}},
    {"type":"FinishedCreatingSurvey","data":{"finishedAt":1577401722688}},
    {"type":"StartedCreatingSurveyCaptureLayoutAggregate","data":{"command":{"aggregateId":"c6966269-d606-4c48-b157-37f005e4b6f9","surveyId":"4f188480-9a39-4335-902b-e481af19b3e5","generatedAt":1577401722691},"startedAt":1577401722689}},
    {"type":"FinishedCreatingSurveyCaptureLayoutAggregate","data":{"finishedAt":1577401722693}},
    {"type":"SurveySagaFinishedSuccessfully","data":{"finishedAt":1577401722693}}
  ]
}
```
```json
{
  "aggregateId":"4f188480-9a39-4335-902b-e481af19b3e5",
  "events":[
    {"type":"Created","data":{"name":{"en":"nameo"},"accountId":"3a94163b-4dd6-47ae-8eeb-a27d93ecde14","createdAt":818380800000}},
    {"type":"Deleted","data":{"deletedAt":818380800000}},
    {"type":"Restored","data":{"restoredAt":818380800000}}
  ]
}
```

## Saga with events coming in from third parties
```shell
curl -v -X POST "localhost:8080/command/survey.demo.StartPaymentSaga" -d '{"aggregateId": "9586ee4b-24d6-4bc5-b1ae-6d440ab0cb60", "fromUserId": "e512c6ff-22e6-4f22-9f07-8986023b9fde", "toUserBankDetails": "12345", "dollarAmount": 5}' -H "accept: application/json" -H "Content-Type: application/json"
curl -v -X POST "localhost:8080/command/survey.demo.RegisterThirdPartySuccess" -d '{"aggregateId": "9586ee4b-24d6-4bc5-b1ae-6d440ab0cb60"}' -H "accept: application/json" -H "Content-Type: application/json"
```

Gives:
```json
{
  "aggregateId":"9586ee4b-24d6-4bc5-b1ae-6d440ab0cb60",
  "events":[
    {"type":"PaymentSagaStarted","data":{"fromUserId":"e512c6ff-22e6-4f22-9f07-8986023b9fde","toUserBankDetails":"12345","dollarAmount":5,"startedAt":1577402146951}},
    {"type":"StartedThirdPartyPayment","data":{"startedAt":1577402146952}},
    {"type":"FinishedThirdPartyPayment","data":{"finishedAt":1577402152015}},
    {"type":"StartedThirdPartyEmailNotification","data":{"message":"successfully paid","startedAt":1577402152016}}
  ]
}
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


