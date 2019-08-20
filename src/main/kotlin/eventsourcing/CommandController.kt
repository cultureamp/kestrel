package eventsourcing

class CommandController(val commandGateway: CommandGateway) {
    fun handle(command: Command): Boolean {
        return commandGateway.dispatch(command)
    }
}
