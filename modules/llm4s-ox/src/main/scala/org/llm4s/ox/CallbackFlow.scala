package org.llm4s.ox

import _root_.ox.{ discard, fork, repeatWhile, supervised, tapException }
import _root_.ox.channels.{ BufferCapacity, Channel, ChannelClosed }
import _root_.ox.flow.Flow
import org.llm4s.error.CancelledError
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicReference
import scala.util.{ Failure, Success, Try }

/** Turns a blocking call that pushes items to a callback into a pull-based Ox `Flow`. */
private[ox] object CallbackFlow {

  /**
   * Sends `item`, waiting while the channel is full. A channel the consumer has closed refuses it with an
   * `InterruptedException`, as an interrupt would: either way the consumer is gone, and llm4s providers return
   * `CancelledError` for both.
   */
  private def offer[A](channel: Channel[A], item: A): Unit =
    channel.sendOrClosed(item) match {
      case _: ChannelClosed => throw new InterruptedException("the flow's consumer has stopped")
      case _                => ()
    }

  /**
   * A `Flow` of what `produce` hands its callback. Each run calls `produce` once, on a forked virtual thread, and
   * its items reach the consumer through a channel of the given capacity, so a lagging consumer blocks `produce`.
   * A `Left` from `produce` fails the flow with an [[LLMException]] after the items before it; an exception thrown
   * by `produce` fails it with that exception. When the consumer stops early, the run's scope interrupts `produce`
   * (core returns `CancelledError`) and waits for it to return.
   */
  def apply[A](operation: String)(produce: (A => Unit) => Result[?])(using BufferCapacity): Flow[A] =
    Flow.usingEmit { emit =>
      val channel = BufferCapacity.newChannel[A]
      // How the producer failed. Set before the channel is closed as done, never closed with an error: a channel
      // closed with an error discards what it buffered, and the items sent before the failure must still arrive.
      val failure = new AtomicReference[Option[Throwable]](None)
      supervised {
        fork {
          Try(CancelledError.attempt(operation)(produce(a => offer(channel, a)))) match {
            case Success(Right(_))    => ()
            case Success(Left(error)) => failure.set(Some(new LLMException(error)))
            case Failure(thrown)      => failure.set(Some(thrown))
          }
          channel.doneOrClosed().discard
        }.discard
        // However the consumer stops (a `take`, a failure, an interrupt), the channel is closed first, so a provider
        // that ignored the interrupt has every later chunk refused at once instead of blocking the scope's end on a
        // full buffer.
        repeatWhile {
          channel.receiveOrClosed() match {
            case ChannelClosed.Done          => failure.get().fold(false)(reason => throw reason)
            case ChannelClosed.Error(reason) => throw reason
            case item                        => emit(item.asInstanceOf[A]); true
          }
        }.tapException(_ => channel.doneOrClosed().discard)
      }
    }
}
