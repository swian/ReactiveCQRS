package io.reactivecqrs.api

import io.reactivecqrs.api.id.{CommandId, AggregateId}


// Command handling default response

object Nothing {
  val empty = Nothing()
}
case class Nothing()

sealed abstract class CustomCommandResponse[INFO]

case class SuccessResponse(aggregateId: AggregateId, aggregateVersion: AggregateVersion) extends CommandResponse
case class CustomSuccessResponse[INFO](aggregateId: AggregateId, aggregateVersion: AggregateVersion, info: INFO) extends CustomCommandResponse[INFO]
case class FailureResponse(exceptions: List[String]) extends CommandResponse
case class AggregateConcurrentModificationError(aggregateId: AggregateId,
                                                aggregateType: String,
                                                expected: AggregateVersion,
                                                was: AggregateVersion) extends CommandResponse
case class CommandHandlingError(commandName: String, stackTrace: String, commandId: CommandId) extends CommandResponse