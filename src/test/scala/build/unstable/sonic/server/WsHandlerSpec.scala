package build.unstable.sonic.server

import java.net.InetAddress

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage, ActorSubscriberMessage}
import akka.testkit.{CallingThreadDispatcher, ImplicitSender, TestActorRef, TestKit}
import build.unstable.sonic.ImplicitSubscriber
import build.unstable.sonic.JsonProtocol._
import build.unstable.sonic.model._
import build.unstable.sonic.server.source.SyntheticPublisher
import build.unstable.sonic.server.system.WsHandler
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.util.Failure

class WsHandlerSpec(_system: ActorSystem) extends TestKit(_system)
with WordSpecLike with Matchers with BeforeAndAfterAll with ImplicitSender
with ImplicitSubscriber with ImplicitGuardian {

  import build.unstable.sonic.Fixture._

  def this() = this(ActorSystem("WsHandlerSpec"))

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def newHandler(): TestActorRef[WsHandler] = {
    val wsHandler =
      TestActorRef[WsHandler](
      Props(classOf[WsHandler], self, Some(InetAddress.getLocalHost))
        .withDispatcher(CallingThreadDispatcher.Id))

    ActorPublisher.apply[SonicMessage](wsHandler).subscribe(subs)
    wsHandler
  }

  def expectProgress(wsHandler: ActorRef): Unit = {
    val prog = QueryProgress(QueryProgress.Running, 1, Some(100.0), None)
    wsHandler ! prog
    expectMsg(prog)
  }

  def expectOutput(wsHandler: ActorRef): Unit = {
    val out = OutputChunk(Vector("1"))
    wsHandler ! out
    expectMsg(out)
  }

  def expectDone(wsHandler: ActorRef): Unit = {
    val done = StreamCompleted.success(syntheticQuery.traceId.get)
    wsHandler ! done
    expectMsg(done)
  }

  def expectIdle(wsHandler: TestActorRef[WsHandler]): Unit = {
    assert(wsHandler.underlyingActor.pendingToStream == 0)
    assert(wsHandler.underlyingActor.subscription == null)
  }

  def clientAcknowledge(wsHandler: TestActorRef[WsHandler]) = {
    val ack = OnNext(ClientAcknowledge)
    wsHandler ! ack
    expectIdle(wsHandler)
  }

  def newMaterializedHandler(props: Props): TestActorRef[WsHandler] = {
    val wsHandler = newHandler()
    wsHandler ! OnNext(syntheticQuery)
    val q = expectMsgType[NewCommand]

    //make sure that traceId is injected
    assert(q.command.traceId.nonEmpty)

    wsHandler ! props
    wsHandler
  }

  "WsHandler" should {
    "handle authenticate message" in {
      val wsHandler = newHandler()

      wsHandler ! Request(1)
      wsHandler ! OnNext(Authenticate("serrallonga", "a", None))

      val q = expectMsgType[NewCommand]
      assert(q.command.traceId.nonEmpty)

      val done = "token"
      wsHandler ! done
      wsHandler ! Request(1)
      expectMsg(OutputChunk(Vector(done)))
      wsHandler ! Request(1)
      val d = expectMsgType[StreamCompleted]
      assert(d.success)

      clientAcknowledge(wsHandler)
      wsHandler ! PoisonPill
      expectMsg("complete")
    }

    "handle authenticate message and auth error" in {
      val wsHandler = newHandler()

      wsHandler ! Request(1)
      wsHandler ! OnNext(Authenticate("serrallonga", "a", None))

      val q = expectMsgType[NewCommand]
      assert(q.command.traceId.nonEmpty)

      val done: Failure[String] = Failure(new Exception("BOOM"))
      wsHandler ! done
      wsHandler ! Request(1)
      val d = expectMsgType[StreamCompleted]
      assert(!d.success)
      assert(d.error.get == done.failed.get)

      clientAcknowledge(wsHandler)
      wsHandler ! PoisonPill
      expectMsg("complete")
    }

    "handle error event when controller fails to instantiate source class" in {
      val wsHandler = newHandler()

      wsHandler ! OnNext(syntheticQuery)

      val q = expectMsgType[NewCommand]
      assert(q.command.traceId.nonEmpty)

      val e = new Exception("BOOM")
      val failure = Failure(e)
      val done = StreamCompleted.error(syntheticQuery.traceId.get, e)
      wsHandler ! failure
      wsHandler ! Request(1)
      expectMsg(done)

      clientAcknowledge(wsHandler)
      wsHandler ! PoisonPill
      expectMsg("complete")
    }

    "should complete stream with error if peer publisher handler terminates unexpectedly" in {
      val wsHandler = newHandler()
      val queryThatTriggersUnexpectedFailure = "-1"
      val syntheticQuery = Query(queryThatTriggersUnexpectedFailure, config, None).copy(query_id = Some(1), trace_id = Some(traceId))
      val syntheticPubProps = Props(classOf[SyntheticPublisher], None, Some(1), 10, queryThatTriggersUnexpectedFailure, false, None, testCtx)
        .withDispatcher(CallingThreadDispatcher.Id)

      wsHandler ! OnNext(syntheticQuery)

      val q = expectMsgType[NewCommand]
      assert(q.command.traceId.nonEmpty)

      wsHandler ! syntheticPubProps
      wsHandler ! Request(1)

      val done = expectMsgType[StreamCompleted]

      val error = done.asInstanceOf[StreamCompleted].error

      error.get.toString.contains("ActorInitializationException") shouldBe true

      clientAcknowledge(wsHandler)
      wsHandler ! PoisonPill
      expectMsg("complete")
    }

    "not call onComplete twice (respect ReactiveStreams rules)" in {
      val wsHandler = guardian.underlying.actor.context.actorOf(Props(classOf[WsHandler], self, None)
        .withDispatcher(CallingThreadDispatcher.Id))

      ActorPublisher.apply[SonicMessage](wsHandler).subscribe(subs)

      wsHandler ! OnNext(syntheticQuery)

      val q = expectMsgType[NewCommand]
      assert(q.command.traceId.nonEmpty)

      subscription.request(1)
      val e = new Exception("BOOM")
      val failure = Failure(e)
      val done = StreamCompleted.error(syntheticQuery.traceId.get, e)
      wsHandler ! failure
      expectMsg(done)

      wsHandler ! PoisonPill
      expectMsg("complete")
    }

    "terminate if upstream completes" in {
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)
        wsHandler ! ActorSubscriberMessage.OnComplete
        expectMsg("complete")
        expectTerminated(wsHandler)
      }
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! ActorSubscriberMessage.OnComplete
        expectMsg("complete")
        expectTerminated(wsHandler)
      }
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! ActorSubscriberMessage.OnComplete
        expectMsg("complete")
        expectTerminated(wsHandler)
      }
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! Request(1)
        expectDone(wsHandler)

        wsHandler ! ActorSubscriberMessage.OnComplete
        expectMsg("complete")
        expectTerminated(wsHandler)
      }
    }

    "terminate if downstream cancels" in {
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! ActorPublisherMessage.Cancel
        expectTerminated(wsHandler)
      }
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)
        wsHandler ! ActorPublisherMessage.Cancel
        expectTerminated(wsHandler)
      }
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! ActorPublisherMessage.Cancel
        expectTerminated(wsHandler)
      }
      {
        val wsHandler = newMaterializedHandler(zombiePubProps)
        watch(wsHandler)

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! Request(1)
        expectDone(wsHandler)

        wsHandler ! ActorPublisherMessage.Cancel
        expectTerminated(wsHandler)
      }
    }

    "forward all messages until completed is called" in {
      val wsHandler = newMaterializedHandler(zombiePubProps)

      wsHandler ! Request(1)
      expectProgress(wsHandler)

      wsHandler ! Request(1)
      expectOutput(wsHandler)

      wsHandler ! Request(1)
      expectDone(wsHandler)

      clientAcknowledge(wsHandler)
      wsHandler ! PoisonPill
      expectMsg("complete")

    }

    "materialize stream and propagates messages to downstream subscriber" in {
      val wsHandler = newMaterializedHandler(syntheticPubProps)

      wsHandler ! Request(1)
      expectMsgClass(classOf[StreamStarted])

      val msgs = (0 until 103).map { i ⇒
        wsHandler ! Request(1)
        expectMsgClass(classOf[SonicMessage])
      }

      msgs.head shouldBe an[TypeMetadata]
      val (progress, tail) = msgs.tail.splitAt(100)
      progress.tail.foreach(_.getClass shouldBe classOf[QueryProgress])
      tail.head shouldBe a[OutputChunk]
      tail.tail.head shouldBe a[StreamCompleted]

      clientAcknowledge(wsHandler)
      wsHandler ! PoisonPill
      expectMsg("complete")

    }

    "handle client cancel message" in {
      val wsHandler = newMaterializedHandler(syntheticPubProps)

      wsHandler ! Request(1)
      expectMsgClass(classOf[StreamStarted])

      wsHandler ! OnNext(CancelStream)
      assert(wsHandler.underlyingActor.subscription.isCancelled)

      wsHandler ! Request(1)
      expectMsg(StreamCompleted.success(syntheticQuery.traceId.get))

      clientAcknowledge(wsHandler)
      clientAcknowledge(wsHandler)
      wsHandler ! PoisonPill
      expectMsg("complete")
    }

    "pipeline multiple commands" in {
      val wsHandler = newHandler()

      //1st query
      {
        wsHandler ! OnNext(syntheticQuery)
        expectMsgType[NewCommand]

        wsHandler ! zombiePubProps

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! Request(1)
        expectDone(wsHandler)

        clientAcknowledge(wsHandler)
      }

      //2c nd query
      {
        wsHandler ! OnNext(syntheticQuery)
        expectMsgType[NewCommand]

        wsHandler ! zombiePubProps

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! Request(1)
        expectDone(wsHandler)

        clientAcknowledge(wsHandler)
      }

      //3rd and 4th queries
      {
        wsHandler ! OnNext(syntheticQuery)
        wsHandler ! OnNext(syntheticQuery)
        expectMsgType[NewCommand]

        wsHandler ! zombiePubProps

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! Request(1)
        expectDone(wsHandler)

        clientAcknowledge(wsHandler)

        //--

        expectMsgType[NewCommand]
        wsHandler ! zombiePubProps

        wsHandler ! Request(1)
        expectProgress(wsHandler)

        wsHandler ! Request(1)
        expectOutput(wsHandler)

        wsHandler ! Request(1)
        expectDone(wsHandler)

        clientAcknowledge(wsHandler)
      }
    }
  }
}
