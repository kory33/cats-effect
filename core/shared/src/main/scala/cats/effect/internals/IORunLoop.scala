/*
 * Copyright (c) 2017-2022 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.internals

import cats.effect.IO
import cats.effect.IO.{Async, Bind, ContextSwitch, Delay, Map, Pure, RaiseError, Suspend, Trace}
import cats.effect.tracing.{IOEvent, IOTrace}
import cats.effect.internals.TracingPlatform.{enhancedExceptions, isStackTracing}

import scala.reflect.NameTransformer
import scala.util.control.NonFatal

private[effect] object IORunLoop {
  private type Current = IO[Any]
  private type Bind = Any => IO[Any]
  private type CallStack = ArrayStack[Bind]
  private type Callback = Either[Throwable, Any] => Unit

  /**
   * Evaluates the given `IO` reference, calling the given callback
   * with the result when completed.
   */
  def start[A](source: IO[A], cb: Either[Throwable, A] => Unit): Unit =
    loop(source, IOConnection.uncancelable, cb.asInstanceOf[Callback], null, null, null, null)

  def restart[A](source: IO[A], ctx: IOContext, cb: Either[Throwable, A] => Unit): Unit =
    loop(source, IOConnection.uncancelable, cb.asInstanceOf[Callback], ctx, null, null, null)

  /**
   * Evaluates the given `IO` reference, calling the given callback
   * with the result when completed.
   */
  def startCancelable[A](source: IO[A], conn: IOConnection, cb: Either[Throwable, A] => Unit): Unit =
    loop(source, conn, cb.asInstanceOf[Callback], null, null, null, null)

  def restartCancelable[A](source: IO[A], conn: IOConnection, ctx: IOContext, cb: Either[Throwable, A] => Unit): Unit =
    loop(source, conn, cb.asInstanceOf[Callback], ctx, null, null, null)

  /**
   * Loop for evaluating an `IO` value.
   *
   * The `rcbRef`, `bFirstRef` and `bRestRef`  parameters are
   * nullable values that can be supplied because the loop needs
   * to be resumed in [[RestartCallback]].
   */
  private def loop(
    source: Current,
    cancelable: IOConnection,
    cb: Either[Throwable, Any] => Unit,
    ctxRef: IOContext,
    rcbRef: RestartCallback,
    bFirstRef: Bind,
    bRestRef: CallStack
  ): Unit = {
    var currentIO: Current = source
    // Can change on a context switch
    var conn: IOConnection = cancelable
    var ctx: IOContext = ctxRef
    var bFirst: Bind = bFirstRef
    var bRest: CallStack = bRestRef
    var rcb: RestartCallback = rcbRef
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null
    // For auto-cancellation
    var currentIndex = 0

    while ({
      currentIO match {
        case bind @ Bind(fa, bindNext, _) =>
          if (isStackTracing) {
            if (ctx eq null) ctx = new IOContext()
            val trace = bind.trace
            if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
          }
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk, trace) =>
          try {
            if (isStackTracing) {
              if (ctx eq null) ctx = new IOContext()
              if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
            }
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch {
            case NonFatal(e) =>
              currentIO = RaiseError(e)
          }

        case Suspend(thunk, trace) =>
          currentIO =
            try {
              if (isStackTracing) {
                if (ctx eq null) ctx = new IOContext()
                if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
              }
              thunk()
            } catch { case NonFatal(ex) => RaiseError(ex) }

        case RaiseError(ex) =>
          if (isStackTracing && enhancedExceptions && ctx != null) {
            augmentException(ex, ctx)
          }
          findErrorHandler(bFirst, bRest) match {
            case null =>
              cb(Left(ex))
              return
            case bind =>
              val fa =
                try bind.recover(ex)
                catch { case NonFatal(e) => RaiseError(e) }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ Map(fa, _, _) =>
          if (isStackTracing) {
            if (ctx eq null) ctx = new IOContext()
            val trace = bindNext.trace
            if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
          }
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case async @ Async(_, _, _) =>
          if (conn eq null) conn = IOConnection()
          // We need to initialize an IOContext because the continuation
          // may produce trace frames e.g. IOBracket.
          if (ctx eq null) ctx = new IOContext()
          if (rcb eq null) rcb = new RestartCallback(conn, cb.asInstanceOf[Callback])
          if (isStackTracing) {
            val trace = async.trace
            if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
          }
          rcb.start(async, ctx, bFirst, bRest)
          return

        case ContextSwitch(next, modify, restore) =>
          val old = if (conn ne null) conn else IOConnection()
          conn = modify(old)
          currentIO = next
          if (conn ne old) {
            if (rcb ne null) rcb.contextSwitch(conn)
            if (restore ne null)
              currentIO = Bind(
                next,
                new RestoreContext(old,
                                   restore.asInstanceOf[(Any, Throwable, IOConnection, IOConnection) => IOConnection]),
                null
              )
          }

        case Trace(source, frame) =>
          if (ctx eq null) ctx = new IOContext()
          ctx.pushEvent(frame)
          currentIO = source
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            cb(Right(unboxed))
            return
          case bind =>
            val fa =
              try bind(unboxed)
              catch { case NonFatal(ex) => RaiseError(ex) }
            hasUnboxed = false
            unboxed = null
            bFirst = null
            currentIO = fa
        }
      }

      // Auto-cancellation logic
      currentIndex += 1
      if (currentIndex == maxAutoCancelableBatchSize) {
        if (conn.isCanceled) return
        currentIndex = 0
      }
      true
    }) ()
  }

  /**
   * Evaluates the given `IO` reference until an asynchronous
   * boundary is hit.
   */
  def step[A](source: IO[A]): IO[A] = {
    var currentIO: Current = source
    var bFirst: Bind = null
    var bRest: CallStack = null
    var ctx: IOContext = null
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    while ({
      currentIO match {
        case bind @ Bind(fa, bindNext, _) =>
          if (isStackTracing) {
            if (ctx eq null) ctx = new IOContext()
            val trace = bind.trace
            if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
          }
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk, trace) =>
          try {
            if (isStackTracing) {
              if (ctx eq null) ctx = new IOContext()
              if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
            }
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch {
            case NonFatal(e) =>
              currentIO = RaiseError(e)
          }

        case Suspend(thunk, trace) =>
          currentIO =
            try {
              if (isStackTracing) {
                if (ctx eq null) ctx = new IOContext()
                if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
              }
              thunk()
            } catch { case NonFatal(ex) => RaiseError(ex) }

        case RaiseError(ex) =>
          if (isStackTracing && enhancedExceptions && ctx != null) {
            augmentException(ex, ctx)
          }
          findErrorHandler(bFirst, bRest) match {
            case null =>
              return currentIO.asInstanceOf[IO[A]]
            case bind =>
              val fa =
                try bind.recover(ex)
                catch { case NonFatal(e) => RaiseError(e) }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ Map(fa, _, _) =>
          if (isStackTracing) {
            if (ctx eq null) ctx = new IOContext()
            val trace = bindNext.trace
            if (trace ne null) ctx.pushEvent(trace.asInstanceOf[IOEvent])
          }
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa
          if (ctx eq null) ctx = new IOContext()

        case Trace(source, frame) =>
          if (ctx eq null) ctx = new IOContext()
          ctx.pushEvent(frame)
          currentIO = source

        case _ =>
          // Cannot inline the code of this method — as it would
          // box those vars in scala.runtime.ObjectRef!
          return suspendAsync(currentIO.asInstanceOf[IO[A]], ctx, bFirst, bRest)
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            return (if (currentIO ne null) currentIO else Pure(unboxed))
              .asInstanceOf[IO[A]]
          case bind =>
            val fa =
              try bind(unboxed)
              catch { case NonFatal(ex) => RaiseError(ex) }
            hasUnboxed = false
            unboxed = null
            bFirst = null
            currentIO = fa
        }
      }
      true
    }) ()
    // $COVERAGE-OFF$
    null // Unreachable code
    // $COVERAGE-ON$
  }

  private def suspendAsync[A](currentIO: IO[A], ctx: IOContext, bFirst: Bind, bRest: CallStack): IO[A] =
    // Encountered an instruction that can't be interpreted synchronously,
    // so suspend it in an Async node that can be invoked later.
    Async { (conn, _, cb) =>
      loop(currentIO, conn, cb.asInstanceOf[Callback], ctx, null, bFirst, bRest)
    }

  /**
   * Pops the next bind function from the stack, but filters out
   * `IOFrame.ErrorHandler` references, because we know they won't do
   * anything — an optimization for `handleError`.
   */
  private def popNextBind(bFirst: Bind, bRest: CallStack): Bind = {
    if ((bFirst ne null) && !bFirst.isInstanceOf[IOFrame.ErrorHandler[_]])
      return bFirst

    if (bRest eq null) return null
    while ({
      val next = bRest.pop()
      if (next eq null) {
        return null
      } else if (!next.isInstanceOf[IOFrame.ErrorHandler[_]]) {
        return next
      }
      true
    }) ()
    // $COVERAGE-OFF$
    null
    // $COVERAGE-ON$
  }

  /**
   * Finds a [[IOFrame]] capable of handling errors in our bind
   * call-stack, invoked after a `RaiseError` is observed.
   */
  private def findErrorHandler(bFirst: Bind, bRest: CallStack): IOFrame[Any, IO[Any]] =
    bFirst match {
      case ref: IOFrame[Any, IO[Any]] @unchecked => ref
      case _ =>
        if (bRest eq null) null
        else {
          while ({
            val ref = bRest.pop()
            if (ref eq null)
              return null
            else if (ref.isInstanceOf[IOFrame[_, _]])
              return ref.asInstanceOf[IOFrame[Any, IO[Any]]]
            true
          }) ()
          // $COVERAGE-OFF$
          null
          // $COVERAGE-ON$
        }
    }

  /**
   * If stack tracing and contextual exceptions are enabled, this
   * function will rewrite the stack trace of a captured exception
   * to include the async stack trace.
   */
  private def augmentException(ex: Throwable, ctx: IOContext): Unit = {
    val stackTrace = ex.getStackTrace
    if (!stackTrace.isEmpty) {
      val augmented = stackTrace(stackTrace.length - 1).getClassName.indexOf('@') != -1
      if (!augmented) {
        val prefix = dropRunLoopFrames(stackTrace)
        val suffix = ctx
          .getStackTraces()
          .flatMap(t => IOTrace.getOpAndCallSite(t.stackTrace))
          .map {
            case (methodSite, callSite) =>
              val op = NameTransformer.decode(methodSite.getMethodName)

              new StackTraceElement(op + " @ " + callSite.getClassName,
                                    callSite.getMethodName,
                                    callSite.getFileName,
                                    callSite.getLineNumber)
          }
          .toArray
        ex.setStackTrace(prefix ++ suffix)
      }
    }
  }

  private def dropRunLoopFrames(frames: Array[StackTraceElement]): Array[StackTraceElement] =
    frames.takeWhile(ste => !runLoopFilter.exists(ste.getClassName.startsWith(_)))

  private[this] val runLoopFilter = List(
    "cats.effect.",
    "scala.runtime."
  )

  /**
   * A `RestartCallback` gets created only once, per [[startCancelable]]
   * (`unsafeRunAsync`) invocation, once an `Async` state is hit,
   * its job being to resume the loop after the boundary, but with
   * the bind call-stack restored
   *
   * This is a trick the implementation is using to avoid creating
   * extraneous callback references on asynchronous boundaries, in
   * order to reduce memory pressure.
   *
   * It's an ugly, mutable implementation.
   * For internal use only, here be dragons!
   */
  final private class RestartCallback(connInit: IOConnection, cb: Callback) extends Callback with Runnable {
    import TrampolineEC.{immediate => ec}

    // can change on a ContextSwitch
    private[this] var conn: IOConnection = connInit
    private[this] var canCall = false
    private[this] var trampolineAfter = false
    private[this] var bFirst: Bind = _
    private[this] var bRest: CallStack = _
    private[this] var ctx: IOContext = _

    // Used in combination with trampolineAfter = true
    private[this] var value: Either[Throwable, Any] = _

    def contextSwitch(conn: IOConnection): Unit =
      this.conn = conn

    def start(task: IO.Async[Any], ctx: IOContext, bFirst: Bind, bRest: CallStack): Unit = {
      canCall = true
      this.bFirst = bFirst
      this.bRest = bRest
      this.trampolineAfter = task.trampolineAfter
      this.ctx = ctx

      // Go, go, go
      task.k(conn, ctx, this)
    }

    private[this] def signal(either: Either[Throwable, Any]): Unit = {
      // Allow GC to collect
      val bFirst = this.bFirst
      val bRest = this.bRest
      val ctx = this.ctx
      this.bFirst = null
      this.bRest = null
      this.ctx = null

      // Auto-cancelable logic: in case the connection was cancelled,
      // we interrupt the bind continuation
      if (!conn.isCanceled) either match {
        case Right(success) =>
          loop(Pure(success), conn, cb, ctx, this, bFirst, bRest)
        case Left(e) =>
          loop(RaiseError(e), conn, cb, ctx, this, bFirst, bRest)
      }
    }

    override def run(): Unit = {
      // N.B. this has to be set to null *before* the signal
      // otherwise a race condition can happen ;-)
      val v = value
      value = null
      signal(v)
    }

    def apply(either: Either[Throwable, Any]): Unit =
      if (canCall) {
        canCall = false
        if (trampolineAfter) {
          this.value = either
          ec.execute(this)
        } else {
          signal(either)
        }
      }
  }

  final private class RestoreContext(
    old: IOConnection,
    restore: (Any, Throwable, IOConnection, IOConnection) => IOConnection
  ) extends IOFrame[Any, IO[Any]] {
    def apply(a: Any): IO[Any] =
      ContextSwitch(Pure(a), current => restore(a, null, old, current), null)
    def recover(e: Throwable): IO[Any] =
      ContextSwitch(RaiseError(e), current => restore(null, e, old, current), null)
  }

  /**
   * Number of iterations before the connection is checked for its
   * cancelled status, to interrupt synchronous flatMap loops.
   */
  private[this] val maxAutoCancelableBatchSize = 512
}
