import eventsourcing.*
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand
import kotlin.reflect.KClass
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3

fun main() {
    val surveyNamesProjection = StubSurveyNamesProjection

    val aggregates = mapOf(
        ThingCommand::class to Configuration(
            ThingAggregate.Companion::create,
            ThingAggregate.Companion::created,
            ThingAggregate::update,
            ThingAggregate::updated,
            ThingAggregate::class.simpleName!!
        ),
        SurveyCaptureLayoutCommand::class to Configuration(
            SurveyCaptureLayoutAggregate.Companion::create,
            SurveyCaptureLayoutAggregate.Companion::created,
            SurveyCaptureLayoutAggregate::update,
            SurveyCaptureLayoutAggregate::updated,
            SurveyCaptureLayoutAggregate::class.simpleName!!
        ),
        SurveyCommand::class to Configuration(
            SurveyAggregate.Companion::create.partial(surveyNamesProjection),
            SurveyAggregate.Companion::created,
            SurveyAggregate::update.partial2(surveyNamesProjection),
            SurveyAggregate::updated,
            SurveyAggregate::class.simpleName!!
        )

    )
    val sagas: Map<KClass<out Command>, AggregateConstructorWithProjection<*, *, Step, *, CommandGateway, *>> = mapOf(
        SurveySagaCreationCommand::class to SurveySaga
    )
    val eventStore = InMemoryEventStore
    val commandGateway = CommandGateway(eventStore, aggregates, sagas)

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}

fun <A,B,C> KFunction2<A,B,C>.partial(a: A): (B) -> C {
    return {b -> invoke(a, b)}
}

fun <A,B,C,D> KFunction3<A,B,C,D>.partial2(b: B): (A, C) -> D {
    return {a,c -> invoke(a, b, c)}
}

