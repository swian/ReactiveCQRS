package io.reactivecqrs.core.saga

import akka.pattern.ask
import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import io.reactivecqrs.api.id.{CommandId, UserId}
import io.reactivecqrs.core.saga.SagaActor.SagaPersisted
import io.reactivecqrs.core.uid.{NewSagasIdsPool, UidGeneratorActor}
import io.reactivecqrs.core.util.ActorLogging

import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object SagaActor {

  case class SagaPersisted(sagaId: Long, respondTo: String, order: SagaInternalOrder, phase: SagaPhase)

}

// Actor per saga -  to recieve messages from command bus?
// TODO manual reply of saga? as saga might not know that previous command succeded? or command idempotency?
abstract class SagaActor extends Actor with ActorLogging {

  implicit val timeout = Timeout(50.seconds)

  import context.dispatcher

  val uidGenerator: ActorRef
  val state: SagaState
  val name: String

  private var nextSagaId = 0L
  private var remainingSagasIds = 0L

  type ReceiveOrder = PartialFunction[SagaInternalOrder, Future[SagaHandlingStatus]]
  type ReceiveRevert = PartialFunction[SagaInternalOrder, Future[SagaRevertHandlingStatus]]

  def handleOrder: ReceiveOrder
  def handleRevert: ReceiveRevert

  private def loadPendingSagas(): Unit = {
    state.loadAllSagas((sagaName: String, sagaId: Long, userId: UserId, respondTo: String, phase: SagaPhase, order: SagaInternalOrder) => {
      println("Continuing saga " + sagaName+" "+sagaId)
      self ! SagaPersisted(sagaId, respondTo, order, phase)
    })
  }

  loadPendingSagas()

  override def receive: Receive = {
    case m: SagaOrder => persistInitialSagaStatusAndSendConfirm(self, takeNextSagaId, sender.path.toString, m)
    case m: SagaPersisted if m.phase == CONTINUES => internalHandleOrderContinue(self, m.sagaId, m.respondTo, m.order)
    case m: SagaPersisted if m.phase == REVERTING => internalHandleRevert(self, m.sagaId, m.respondTo, m.order)
    case m => log.warning("Received incorrect message of type: [" + m.getClass.getName +"] "+m)
  }

  def persistInitialSagaStatusAndSendConfirm(me: ActorRef, sagaId: Long, respondTo: String, order: SagaOrder): Unit = {
    Future {
      state.createSaga(name, sagaId, respondTo, order)
      me ! SagaPersisted(sagaId, respondTo, order, CONTINUES)
    } onFailure {
      case e: Exception => throw new IllegalStateException(e)
    }
  }

  def persistSagaStatusAndSendConfirm(me: ActorRef, sagaId: Long, respondTo: String, order: SagaInternalOrder, phase: SagaPhase): Unit = {
    Future {
      state.updateSaga(name, sagaId, order, phase)
      me ! SagaPersisted(sagaId, respondTo, order, phase)
    } onFailure {
      case e: Exception => throw new IllegalStateException(e)
    }
  }

  def deleteSagaStatus(sagaId: Long): Unit = {
    state.deleteSaga(name, sagaId)
  }

  private def internalHandleOrderContinue(me: ActorRef, sagaId: Long, respondTo: String, order: SagaInternalOrder): Unit = {
    Future {
      handleOrder(order).onComplete {
        case Success(r) =>
          r match {
          case c: SagaContinues =>
            persistSagaStatusAndSendConfirm(me, sagaId, respondTo, c.order, CONTINUES)
          case c: SagaSucceded =>
            context.system.actorSelection(respondTo) ! c.response
            deleteSagaStatus(sagaId)
          case c: SagaFailed =>
            context.system.actorSelection(respondTo) ! c.response
            persistSagaStatusAndSendConfirm(me, sagaId, respondTo, order, REVERTING)
        }
        case Failure(ex) =>
          context.system.actorSelection(respondTo) ! SagaFailureResponse(List(ex.getMessage))
          persistSagaStatusAndSendConfirm(me, sagaId, respondTo, order, REVERTING)
      }
    } onFailure {
      case e: Exception => throw new IllegalStateException(e)
    }
  }


  private def internalHandleRevert(me: ActorRef, sagaId: Long, respondTo: String, order: SagaInternalOrder): Unit = {
    Future {
      handleRevert(order).onComplete {
        case Success(r) =>
          r match {
            case c: SagaRevertContinues =>
              persistSagaStatusAndSendConfirm(me, sagaId, respondTo, c.order, REVERTING)
            case SagaRevertSucceded =>
              deleteSagaStatus(sagaId)
            case c: SagaRevertFailed =>
              log.error(s"Saga revert failed, sagaId = $sagaId, order = $order, "+ c.exceptions.mkString(", "))
              deleteSagaStatus(sagaId)
          }
        case Failure(ex) =>
          log.error(ex, s"Saga revert failed, sagaId = $sagaId, order = $order")
          deleteSagaStatus(sagaId)
      }
    } onFailure {
      case e: Exception => throw new IllegalStateException(e)
    }
  }

  private def takeNextSagaId: Long = {
    if(remainingSagasIds == 0) {
      // TODO get rid of ask pattern
      implicit val timeout = Timeout(10 seconds)
      val pool: Future[NewSagasIdsPool] = (uidGenerator ? UidGeneratorActor.GetNewSagasIdsPool).mapTo[NewSagasIdsPool]
      val newSagasIdsPool: NewSagasIdsPool = Await.result(pool, 10 seconds)
      remainingSagasIds = newSagasIdsPool.size
      nextSagaId = newSagasIdsPool.from
    }

    remainingSagasIds -= 1
    val sagaId = nextSagaId
    nextSagaId += 1
    sagaId


  }

}

