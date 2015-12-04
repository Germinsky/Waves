package scorex.network

import java.net.InetSocketAddress

import akka.actor.FSM
import scorex.app.Application
import scorex.block.Block
import scorex.network.BlockGenerator._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}


case class NewBlock(block: Block, sender: Option[InetSocketAddress])

class BlockGenerator(application: Application) extends FSM[Status, Unit] {

  startWith(Syncing, Unit)

  when(Syncing) {
    case Event(StartGeneration, _) => goto(Generating)
  }

  when(Generating, 1.second) {
    case Event(StateTimeout, _) =>
      tryToGenerateABlock()
      stay()

    case Event(StopGeneration, _) => goto(Syncing)
  }

  whenUnhandled {
    case Event(GetStatus, _) =>
      sender() ! stateName.name
      stay()
  }

  initialize()

  def tryToGenerateABlock(): Unit = {
    implicit val transactionalModule = application.transactionModule

    log.info("Trying to generate a new block")
    val accounts = application.wallet.privateKeyAccounts()
    application.consensusModule.generateNextBlocks(accounts)(application.transactionModule) onComplete {
      case Success(blocks: Seq[Block]) =>
        if (blocks.nonEmpty) {
          val bestBlock = blocks.maxBy(application.consensusModule.blockScore)
          self ! NewBlock(bestBlock, None)
        }
      case Failure(ex) => log.error("Failed to generate new block: {}", ex)
      case m => log.error("Unexpected message: {}", m)
    }
  }
}

object BlockGenerator {

  sealed trait Status {
    val name: String
  }

  case object Syncing extends Status {
    override val name = "syncing"
  }

  case object Generating extends Status {
    override val name = "generating"
  }

  case object GetStatus

  case object StartGeneration

  case object StopGeneration
}