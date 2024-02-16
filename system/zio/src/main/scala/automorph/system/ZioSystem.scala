package automorph.system

import automorph.spi.AsyncEffectSystem
import automorph.spi.AsyncEffectSystem.Completable
import zio.{IO, Queue, Runtime, Trace, Unsafe, ZIO}

/**
 * ZIO effect system plugin using `IO` as an effect type.
 *
 * @see
 *   [[https://zio.dev Library documentation]]
 * @see
 *   [[https://javadoc.io/doc/dev.zio/zio_3/latest/zio.html#IO-0 Effect type]]
 * @constructor
 *   Creates a ZIO effect system without environment requirements and with specified error type.
 * @param mapException
 *   maps an exception to a corresponding ZIO error
 * @param mapError
 *   maps a ZIO error to a corresponding exception
 * @param runtime
 *   ZIO runtime
 * @tparam Fault
 *   ZIO error type
 */
final case class ZioSystem[Fault](
  mapException: Throwable => Fault,
  mapError: (Fault, Trace) => Throwable = ZioSystem.mapError,
)(
  implicit val runtime: Runtime[Any]
) extends AsyncEffectSystem[({ type Effect[A] = IO[Fault, A] })#Effect] {

  override def evaluate[T](value: => T): IO[Fault, T] =
    ZIO.attempt(value).mapError(mapException)

  override def successful[T](value: T): IO[Fault, T] =
    ZIO.succeed(value)

  override def failed[T](exception: Throwable): IO[Fault, T] =
    ZIO.fail(exception).mapError(mapException)

  override def either[T](effect: => IO[Fault, T]): IO[Fault, Either[Throwable, T]] =
    effect.fold(
      error => Left(mapError(error, implicitly[Trace])).withRight[T],
      value => Right(value),
    )

  override def flatMap[T, R](effect: IO[Fault, T])(function: T => IO[Fault, R]): IO[Fault, R] =
    effect.flatMap(function)

  override def runAsync[T](effect: IO[Fault, T]): Unit = {
    implicit val trace: Trace = Trace.empty
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.fork(effect)
      ()
    }
  }

  override def completable[T]: IO[Fault, Completable[({ type Effect[A] = IO[Fault, A] })#Effect, T]] =
    map(Queue.dropping[Either[Fault, T]](1))(CompletableZIO.apply)

  sealed private case class CompletableZIO[T](private val queue: Queue[Either[Fault, T]])
    extends Completable[({ type Effect[A] = IO[Fault, A] })#Effect, T]() {

    override def effect: IO[Fault, T] =
      queue.take.flatMap {
        case Right(value) => successful(value)
        case Left(error) => failed(mapError(error, implicitly[Trace]))
      }

    override def succeed(value: T): IO[Fault, Unit] =
      map(queue.offer(Right(value)))(_ => ())

    override def fail(exception: Throwable): IO[Fault, Unit] =
      map(queue.offer(Left(mapException(exception))))(_ => ())
  }
}

object ZioSystem {

  /**
   * ZIO effect type without environment requirements and with specified error type.
   *
   * @tparam T
   *   effectful value type
   * @tparam Fault
   *   ZIO error type
   */
  type Effect[T, Fault] = IO[Fault, T]

  /**
   * Creates a ZIO effect system plugin without specific environment and with errors handling using exceptions.
   *
   * @see
   *   [[https://zio.dev Library documentation]]
   * @see
   *   [[https://javadoc.io/doc/dev.zio/zio_3/latest/zio.html#Task-0 Effect type]]
   * @param runtime
   *   ZIO runtime
   * @return
   *   ZIO effect system plugin
   */
  def withTask(implicit runtime: Runtime[Any] = Runtime.default): ZioSystem[Throwable] =
    ZioSystem[Throwable](identity, (error: Throwable, _: Trace) => error)

  /**
   * Maps a ZIO error to a RuntimeException containing the error trace.
   *
   * @param error
   *   ZIO error
   * @param trace
   *   ZIO trace
   * @tparam Fault
   *   ZIO error type
   * @return
   */
  def mapError[Fault](error: Fault, trace: Trace): Throwable =
    new RuntimeException(s"$error\n$trace")
}
