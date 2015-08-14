package io.reactivecqrs.core.commandhandler

import akka.actor.{Actor, ActorRef, Props}
import akka.event.LoggingReceive
import io.reactivecqrs.api._
import io.reactivecqrs.api.id.{AggregateId, CommandId}
import io.reactivecqrs.core.aggregaterepository.AggregateRepositoryActor.{GetAggregateRoot, PersistEvents}
import io.reactivecqrs.core.commandhandler.CommandHandlerActor.{InternalFirstCommandEnvelope, InternalFollowingCommandEnvelope}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object CommandHandlerActor {

  sealed trait InternalCommandEnvelope[AGGREGATE_ROOT, +RESPONSE]

  case class InternalFirstCommandEnvelope[AGGREGATE_ROOT, RESPONSE](respondTo: ActorRef, commandId: CommandId, commandEnvelope: FirstCommand[AGGREGATE_ROOT, RESPONSE])
    extends InternalCommandEnvelope[AGGREGATE_ROOT, RESPONSE]

  case class InternalFollowingCommandEnvelope[AGGREGATE_ROOT, RESPONSE](respondTo: ActorRef, commandId: CommandId, commandEnvelope: Command[AGGREGATE_ROOT, RESPONSE])
    extends InternalCommandEnvelope[AGGREGATE_ROOT, RESPONSE]


  case class NoAggregateExist()

}


class CommandHandlerActor[AGGREGATE_ROOT](aggregateId: AggregateId,
                                          repositoryActor: ActorRef,
                                          commandHandlers: AGGREGATE_ROOT => PartialFunction[Any, CommandResult[Any]],
                                           initialState: () => AGGREGATE_ROOT) extends Actor {
  
  var resultAggregatorsCounter = 0

  val responseTimeout = 5.seconds

  private def waitingForCommand = LoggingReceive {
    case commandEnvelope: InternalFirstCommandEnvelope[_, _] =>
      handleFirstCommand(commandEnvelope.asInstanceOf[InternalFirstCommandEnvelope[AGGREGATE_ROOT, Any]])
    case commandEnvelope: InternalFollowingCommandEnvelope[_, _] =>
      requestAggregateForCommandHandling(commandEnvelope.asInstanceOf[InternalFollowingCommandEnvelope[AGGREGATE_ROOT, Any]])
  }

  private def waitingForAggregate(command: InternalFollowingCommandEnvelope[AGGREGATE_ROOT, _]) = LoggingReceive {
    case s:Success[_] => handleFollowingCommand(command, s.get.asInstanceOf[Aggregate[AGGREGATE_ROOT]])
    case f:Failure[_] => throw new IllegalStateException("Error getting aggregate")
  }


  override def receive = waitingForCommand

  private def handleFirstCommand[COMMAND <: FirstCommand[AGGREGATE_ROOT, RESPONSE], RESPONSE](envelope: InternalFirstCommandEnvelope[AGGREGATE_ROOT, RESPONSE]) = envelope match {
    case InternalFirstCommandEnvelope(respondTo, commandId, command) =>

      val result:CommandResult[Any] = commandHandlers(initialState())(command.asInstanceOf[FirstCommand[AGGREGATE_ROOT, Any]])

      result match {
        case s: CommandSuccess[_, _] =>
          val success: CommandSuccess[AGGREGATE_ROOT, RESPONSE] = s.asInstanceOf[CommandSuccess[AGGREGATE_ROOT, RESPONSE]]
          val resultAggregator = context.actorOf(Props(new ResultAggregator[RESPONSE](respondTo, success.response(aggregateId, AggregateVersion(s.events.size)), responseTimeout)), nextResultAggregatorName)
          repositoryActor ! PersistEvents[AGGREGATE_ROOT](resultAggregator, aggregateId, commandId, command.userId, AggregateVersion.ZERO, success.events)
        case failure: CommandFailure[_, _] =>
          respondTo ! failure.response
      }

  }

  private def nextResultAggregatorName[RESPONSE, COMMAND <: Command[AGGREGATE_ROOT, RESPONSE]]: String = {
    resultAggregatorsCounter += 1
    "ResultAggregator_" + resultAggregatorsCounter
  }

  private def requestAggregateForCommandHandling[COMMAND <: Command[AGGREGATE_ROOT, RESPONSE], RESPONSE](commandEnvelope: InternalFollowingCommandEnvelope[AGGREGATE_ROOT, RESPONSE]): Unit = {
    context.become(waitingForAggregate(commandEnvelope))
    repositoryActor ! GetAggregateRoot(self)
  }

  private def handleFollowingCommand[COMMAND <: Command[AGGREGATE_ROOT, RESPONSE], RESPONSE](envelope: InternalFollowingCommandEnvelope[AGGREGATE_ROOT, RESPONSE], aggregate: Aggregate[AGGREGATE_ROOT]): Unit = envelope match {
    case InternalFollowingCommandEnvelope(respondTo, commandId, command) =>


      val result = commandHandlers(aggregate.aggregateRoot.get)(command.asInstanceOf[Command[AGGREGATE_ROOT, Any]])

      result match {
        case s: CommandSuccess[_, _] =>
          val success = s.asInstanceOf[CommandSuccess[AGGREGATE_ROOT, RESPONSE]]
          val resultAggregator = context.actorOf(Props(new ResultAggregator[RESPONSE](respondTo, success.response(aggregateId, command.expectedVersion.incrementBy(success.events.size)), responseTimeout)), nextResultAggregatorName)
          repositoryActor ! PersistEvents[AGGREGATE_ROOT](resultAggregator, aggregateId, commandId, command.userId, command.expectedVersion, success.events)
        case failure: CommandFailure[_, _] =>
          respondTo ! failure.response
      }
      context.become(waitingForCommand)
  }



}
