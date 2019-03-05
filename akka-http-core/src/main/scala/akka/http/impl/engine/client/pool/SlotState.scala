/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client.pool

import akka.annotation.InternalApi
import akka.http.impl.engine.client.PoolFlow.RequestContext
import akka.http.impl.util._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ConnectionPoolSettings

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

/**
 * Internal API
 *
 * Interface between slot states and the actual slot.
 */
@InternalApi
private[pool] abstract class SlotContext {
  def openConnection(): Unit
  def isConnectionClosed: Boolean

  def pushRequestToConnectionAndThen(request: HttpRequest, nextState: SlotState): SlotState
  def dispatchResponseResult(req: RequestContext, result: Try[HttpResponse]): Unit

  def willCloseAfter(res: HttpResponse): Boolean

  def debug(msg: String): Unit
  def debug(msg: String, arg1: AnyRef): Unit
  def debug(msg: String, arg1: AnyRef, arg2: AnyRef): Unit
  def debug(msg: String, arg1: AnyRef, arg2: AnyRef, arg3: AnyRef): Unit

  def warning(msg: String): Unit
  def warning(msg: String, arg1: AnyRef): Unit

  def settings: ConnectionPoolSettings
}

/* Internal API */
@InternalApi
private[pool] sealed abstract class SlotState extends Product {
  def isIdle: Boolean
  def isConnected: Boolean

  def onPreConnect(ctx: SlotContext): SlotState = illegalState(ctx, "onPreConnect")
  def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = illegalState(ctx, "onConnectionAttemptSucceeded")
  def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onConnectionAttemptFailed")

  def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState = illegalState(ctx, "onNewRequest")

  /** Will be called either immediately if the request entity is strict or otherwise later */
  def onRequestEntityCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "onRequestEntityCompleted")
  def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onRequestEntityFailed")

  def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState = illegalState(ctx, "onResponseReceived")

  /** Called when the response out port is ready to receive a further response (successful or failed) */
  def onResponseDispatchable(ctx: SlotContext): SlotState = illegalState(ctx, "onResponseDispatchable")

  def onResponseEntitySubscribed(ctx: SlotContext): SlotState = illegalState(ctx, "onResponseEntitySubscribed")

  /** Will be called either immediately if the response entity is strict or otherwise later */
  def onResponseEntityCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "onResponseEntityCompleted")
  def onResponseEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onResponseEntityFailed")

  def onConnectionCompleted(ctx: SlotContext): SlotState = illegalState(ctx, "onConnectionCompleted")
  def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = illegalState(ctx, "onConnectionFailed")

  def onTimeout(ctx: SlotContext): SlotState = illegalState(ctx, "onTimeout")

  def onShutdown(ctx: SlotContext): Unit = ()

  /** A slot can define a timeout for that state after which onTimeout will be called. */
  def stateTimeout: Duration = Duration.Inf

  protected def illegalState(ctx: SlotContext, what: String): SlotState = {
    ctx.debug(s"Got unexpected event [$what] in state [$name]]")
    throw new IllegalStateException(s"Cannot [$what] when in state [$name]")
  }

  def name: String = productPrefix
}

/**
 * Internal API
 *
 * Implementation of slot logic that is completed decoupled from the machinery bits which are implemented in the GraphStageLogic
 * and exposed only through [[SlotContext]].
 */
@InternalApi
private[pool] object SlotState {
  sealed abstract class ConnectedState extends SlotState {
    def isConnected: Boolean = true
  }
  sealed trait IdleState extends SlotState {
    final override def isIdle = true
  }
  sealed private[pool] /* to avoid warnings */ trait BusyState extends SlotState {
    // no HTTP pipelining: we could accept a new request when the request has been sent completely (or
    // even when the response has started to come in). However, that would mean the next request and response
    // are effectively blocked on the completion on the previous request and response. For this reason we
    // avoid accepting new connections in this slot while the previous request is still in progress: there might
    // be another slot available which can process the request with lower latency.
    final override def isIdle = false
    def ongoingRequest: RequestContext
    def waitingForEndOfRequestEntity: Boolean

    override def onShutdown(ctx: SlotContext): Unit = {
      // We would like to dispatch a failure here but responseOut might not be ready (or also already shutting down)
      // so we cannot do more than logging the problem here.

      ctx.warning(s"Ongoing request [{}] was dropped because pool is shutting down", ongoingRequest.request.debugString)

      super.onShutdown(ctx)
    }

    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState =
      // TODO: register failed connection attempt to be able to backoff (see https://github.com/akka/akka-http/issues/1391)
      failOngoingRequest(ctx, "connection attempt failed", cause)

    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = failOngoingRequest(ctx, "request entity stream failed", cause)
    override def onConnectionCompleted(ctx: SlotContext): SlotState =
      // There's no good reason why the connection stream (i.e. the user-facing client Flow[HttpRequest, HttpResponse])
      // would complete during processing of a request.
      // One reason might be that failures on the TCP layer don't necessarily propagate through the stack as failures
      // because of the notorious cancel/failure propagation which can convert failures into completion.
      failOngoingRequest(ctx, "connection completed", new IllegalStateException("Connection was shutdown.") with NoStackTrace)

    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = failOngoingRequest(ctx, "connection failure", cause)

    private def failOngoingRequest(ctx: SlotContext, signal: String, cause: Throwable): SlotState = {
      ctx.debug("Ongoing request [{}] is failed because of [{}]: [{}]", ongoingRequest.request.debugString, signal, cause.getMessage)
      if (ongoingRequest.canBeRetried) { // push directly because it will be buffered internally
        ctx.dispatchResponseResult(ongoingRequest, Failure(cause))
        if (waitingForEndOfRequestEntity) WaitingForEndOfRequestEntity
        else Unconnected
      } else
        WaitingForResponseDispatch(ongoingRequest, Failure(cause), waitingForEndOfRequestEntity)
    }
  }

  case object Unconnected extends SlotState with IdleState {
    def isConnected: Boolean = false

    override def onPreConnect(ctx: SlotContext): SlotState = {
      ctx.openConnection()
      PreConnecting
    }

    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState = {
      ctx.openConnection()
      Connecting(requestContext)
    }
  }
  case object Idle extends ConnectedState with IdleState with WithRequestDispatching {
    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState =
      dispatchRequestToConnection(ctx, requestContext)

    override def onConnectionCompleted(ctx: SlotContext): SlotState = Unconnected
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = Unconnected
  }
  sealed trait WithRequestDispatching { _: ConnectedState ⇒
    def dispatchRequestToConnection(ctx: SlotContext, ongoingRequest: RequestContext): SlotState =
      ctx.pushRequestToConnectionAndThen(ongoingRequest.request, WaitingForResponse(ongoingRequest, waitingForEndOfRequestEntity = true))
  }

  final case class Connecting(ongoingRequest: RequestContext) extends ConnectedState with BusyState with WithRequestDispatching {
    val waitingForEndOfRequestEntity = false

    override def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was established")
      dispatchRequestToConnection(ctx, ongoingRequest)
    }
    // connection failures are handled by BusyState implementations
  }

  case object PreConnecting extends ConnectedState with IdleState with WithRequestDispatching {
    override def onConnectionAttemptSucceeded(ctx: SlotContext, outgoingConnection: Http.OutgoingConnection): SlotState = {
      ctx.debug("Slot connection was (pre-)established")
      Idle
    }
    override def onNewRequest(ctx: SlotContext, requestContext: RequestContext): SlotState =
      Connecting(requestContext)

    override def onConnectionAttemptFailed(ctx: SlotContext, cause: Throwable): SlotState =
      // TODO: register failed connection attempt to be able to backoff (see https://github.com/akka/akka-http/issues/1391)
      closeAndGoToUnconnected(ctx, "connection attempt failed", cause)
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState =
      closeAndGoToUnconnected(ctx, "connection failed", cause)
    override def onConnectionCompleted(ctx: SlotContext): SlotState =
      closeAndGoToUnconnected(ctx, "connection completed", new IllegalStateException("Unexpected connection closure") with NoStackTrace)

    private def closeAndGoToUnconnected(ctx: SlotContext, signal: String, cause: Throwable): SlotState = {
      ctx.debug("Connection was closed by [{}] while preconnecting because of [{}]", signal, cause.getMessage)
      Unconnected
    }
  }
  final case class WaitingForResponse(ongoingRequest: RequestContext, waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyState {

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForResponse(ongoingRequest, waitingForEndOfRequestEntity = false)
    }

    override def onResponseReceived(ctx: SlotContext, response: HttpResponse): SlotState = {
      ctx.debug(s"onResponseReceived in WaitingForResponse with $waitingForEndOfRequestEntity")
      WaitingForResponseDispatch(ongoingRequest, Success(response), waitingForEndOfRequestEntity)
    }

    // connection failures are handled by BusyState implementations
  }
  final case class WaitingForResponseDispatch(
    ongoingRequest:               RequestContext,
    result:                       Try[HttpResponse],
    waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyWithResultAlreadyDetermined {

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForResponseDispatch(ongoingRequest, result, waitingForEndOfRequestEntity = false)
    }

    /** Called when the response out port is ready to receive a further response (successful or failed) */
    override def onResponseDispatchable(ctx: SlotContext): SlotState = {
      ctx.dispatchResponseResult(ongoingRequest, result)

      result match {
        case Success(res)   ⇒ WaitingForResponseEntitySubscription(ongoingRequest, res, ctx.settings.responseEntitySubscriptionTimeout, waitingForEndOfRequestEntity)
        case Failure(cause) ⇒ Unconnected
      }
    }
  }

  private[pool] /* to avoid warnings */ trait BusyWithResultAlreadyDetermined extends ConnectedState with BusyState {
    override def onResponseEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = {
      ctx.debug(s"Response entity for request [{}] failed with [{}]", ongoingRequest.request.debugString, cause.getMessage)
      // response must have already been dispatched, so don't try to dispatch a response
      Unconnected
    }

    // ignore since we already accepted the request
    override def onConnectionCompleted(ctx: SlotContext): SlotState = this
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = this
    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState = this
  }

  final case class WaitingForResponseEntitySubscription(
    ongoingRequest:  RequestContext,
    ongoingResponse: HttpResponse, override val stateTimeout: Duration, waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyWithResultAlreadyDetermined {

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForResponseEntitySubscription(ongoingRequest, ongoingResponse, stateTimeout, waitingForEndOfRequestEntity = false)
    }

    override def onResponseEntitySubscribed(ctx: SlotContext): SlotState =
      WaitingForEndOfResponseEntity(ongoingRequest, ongoingResponse, waitingForEndOfRequestEntity)

    override def onTimeout(ctx: SlotContext): SlotState = {
      ctx.warning(
        s"Response entity was not subscribed after $stateTimeout. Make sure to read the response entity body or call `discardBytes()` on it. " +
          s"${ongoingRequest.request.debugString} -> ${ongoingResponse.debugString}")
      Unconnected
    }

  }
  final case class WaitingForEndOfResponseEntity(
    ongoingRequest:               RequestContext,
    ongoingResponse:              HttpResponse,
    waitingForEndOfRequestEntity: Boolean) extends ConnectedState with BusyWithResultAlreadyDetermined {

    override def onResponseEntityCompleted(ctx: SlotContext): SlotState =
      if (waitingForEndOfRequestEntity)
        WaitingForEndOfRequestEntity
      // TODO can we be *sure* that by skipping to Unconnected if ctx.willCloseAfter(ongoingResponse)
      // we can't get a connection closed event from the 'previous' connection later?
      else if (ctx.willCloseAfter(ongoingResponse) || ctx.isConnectionClosed)
        Unconnected
      else
        Idle

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState = {
      require(waitingForEndOfRequestEntity)
      WaitingForEndOfResponseEntity(ongoingRequest, ongoingResponse, waitingForEndOfRequestEntity = false)
    }
  }
  final case object WaitingForEndOfRequestEntity extends ConnectedState {
    final override def isIdle = false

    override def onRequestEntityCompleted(ctx: SlotContext): SlotState =
      if (ctx.isConnectionClosed) Unconnected
      else Idle
    override def onRequestEntityFailed(ctx: SlotContext, cause: Throwable): SlotState =
      if (ctx.isConnectionClosed) Unconnected
      else Idle
    override def onConnectionCompleted(ctx: SlotContext): SlotState = Unconnected
    override def onConnectionFailed(ctx: SlotContext, cause: Throwable): SlotState = Unconnected
  }
}