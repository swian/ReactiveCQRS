package io.reactivecqrs.actor

import akka.actor.{ActorRef, Actor, Props}
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import io.reactivecqrs.actor.AggregateCommandBusActor.{FirstCommandEnvelope, FollowingCommandEnvelope}
import io.reactivecqrs.actor.AggregateRepositoryActor.ReturnAggregateRoot
import io.reactivecqrs.actor.CommandHandlerActor.{InternalFirstCommandEnvelope, InternalCommandEnvelope}
import io.reactivecqrs.api.guid.{UserId, CommandId, AggregateId}
import io.reactivecqrs.core._
import io.reactivecqrs.uid.{NewCommandsIdsPool, NewAggregatesIdsPool, UidGeneratorActor}

import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag
import scala.concurrent.duration._


object AggregateCommandBusActor {


  object CommandEnvelope {

    def apply[AGGREGATE_ROOT, RESPONSE](userId: UserId, command: FirstCommand[AGGREGATE_ROOT, RESPONSE]) = FirstCommandEnvelope(userId, command)
    def apply[AGGREGATE_ROOT, RESPONSE](userId: UserId, aggregateId: AggregateId,
                                        expectedVersion: AggregateVersion, command: Command[AGGREGATE_ROOT, RESPONSE]) = FollowingCommandEnvelope(userId,aggregateId, expectedVersion, command)

  }
  case class FirstCommandEnvelope[AGGREGATE_ROOT, RESPONSE](userId: UserId,
                                                            command: FirstCommand[AGGREGATE_ROOT, RESPONSE])



  case class FollowingCommandEnvelope[AGGREGATE_ROOT, RESPONSE](userId: UserId,
                                                                aggregateId: AggregateId,
                                                                expectedVersion: AggregateVersion,
                                                                command: Command[AGGREGATE_ROOT, RESPONSE])



}

class AggregateCommandBusActor[AGGREGATE_ROOT](val uidGenerator: ActorRef,
                                      val commandsHandlers: Seq[CommandHandler[AGGREGATE_ROOT,AbstractCommand[AGGREGATE_ROOT, _],_]],
                                     val eventsHandlers: Seq[AbstractEventHandler[AGGREGATE_ROOT, Event[AGGREGATE_ROOT]]])
                                    (implicit aggregateRootClassTag: ClassTag[AGGREGATE_ROOT]) extends Actor {




  var nextAggregateId = 0L
  var remainingAggregateIds = 0L
  
  var nextCommandId = 0L
  var remainingCommandsIds = 0L



  override def receive: Receive = LoggingReceive {
    case ce: FollowingCommandEnvelope[_,_] => routeCommand(ce.asInstanceOf[FollowingCommandEnvelope[AGGREGATE_ROOT, _]])
    case fce: FirstCommandEnvelope[_,_] => routeFirstCommand(fce.asInstanceOf[FirstCommandEnvelope[AGGREGATE_ROOT, _]])
    case GetAggregate(id) => routeGetAggregateRoot(id)
    case m => throw new IllegalArgumentException("Cannot handle this kind of message: " + m + " class: " + m.getClass)
  }

  def routeCommand[RESPONSE](command: FollowingCommandEnvelope[AGGREGATE_ROOT, RESPONSE]): Unit = {
    println("Routes non first command")
    val commandId = takeNextCommandId
    val existingCommandHandlerActor = context.actorSelection("CommandHandler" + command.aggregateId.asLong)
    val respondTo = sender()
    existingCommandHandlerActor ! InternalCommandEnvelope(respondTo, commandId, command)

  }

  def routeFirstCommand[RESPONSE](firstCommand: FirstCommandEnvelope[AGGREGATE_ROOT, RESPONSE]): Unit = {
    println("Routes first command")
    val commandId = takeNextCommandId
    val newAggregateId = takeNextAggregateId // Actor construction might be delayed so we need to store current aggregate id
    val respondTo = sender() // with sender this shouldn't be the case, but just to be sure
    val newCommandHandlerActor = context.actorOf(Props(new CommandHandlerActor[AGGREGATE_ROOT](newAggregateId, commandsHandlers, eventsHandlers)), "CommandHandler" + newAggregateId.asLong)
    newCommandHandlerActor ! InternalFirstCommandEnvelope(respondTo, commandId, firstCommand)
  }

  def routeGetAggregateRoot(id: AggregateId): Unit = {
    println(s"Routes routeGetAggregateRoot $id")
    val respondTo = sender()
    val aggregate = context.actorSelection("CommandHandler" + id.asLong+"/" + "AggregateRepository"+id.asLong)
    aggregate ! ReturnAggregateRoot(respondTo)
  }

  private def takeNextAggregateId: AggregateId = {
    if(remainingAggregateIds == 0) {
      // TODO get rid of ask pattern
      implicit val timeout = Timeout(5 seconds)
      val pool: Future[NewAggregatesIdsPool] = (uidGenerator ? UidGeneratorActor.GetNewAggregatesIdsPool).mapTo[NewAggregatesIdsPool]
      val newAggregatesIdsPool: NewAggregatesIdsPool = Await.result(pool, 5 seconds)
      remainingAggregateIds = newAggregatesIdsPool.size
      nextAggregateId = newAggregatesIdsPool.from
    }

    remainingAggregateIds -= 1
    val aggregateId = AggregateId(nextAggregateId)
    nextAggregateId += 1
    aggregateId


  }

  private def takeNextCommandId: CommandId = {
    if(remainingCommandsIds == 0) {
      // TODO get rid of ask pattern
      implicit val timeout = Timeout(5 seconds)
      val pool: Future[NewCommandsIdsPool] = (uidGenerator ? UidGeneratorActor.GetNewCommandsIdsPool).mapTo[NewCommandsIdsPool]
      val newCommandsIdsPool: NewCommandsIdsPool = Await.result(pool, 5 seconds)
      remainingCommandsIds = newCommandsIdsPool.size
      nextCommandId = newCommandsIdsPool.from
    }

    remainingCommandsIds -= 1
    val commandId = CommandId(nextCommandId)
    nextCommandId += 1
    commandId


  }
}