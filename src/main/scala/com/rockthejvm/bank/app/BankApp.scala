package com.rockthejvm.bank.app

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.rockthejvm.bank.actors.Bank
import com.rockthejvm.bank.actors.PersistentBankAccount.Command
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.rockthejvm.bank.http.BankRoutes

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Try, Success, Failure}

object BankApp {

  def startHttpServer(bank: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    val router = new BankRoutes(bank)
    val routes = router.routes

    val httpBindingFuture = Http().newServerAt("localhost", 8080).bind(routes)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"Server started at http://${address.getHostName}:${address.getPort}")
      case Failure(exception) =>
        system.log.error(s"Server was failed, because of $exception")
        system.terminate()

    }
  }

  def main(args: Array[String]): Unit = {
    trait RootCommand
    case class RetrieveBankActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

    val rootBehaviour: Behavior[RootCommand] = Behaviors.setup { context =>
      val bankActor = context.spawn(Bank(), "bank")
      Behaviors.receiveMessage {
        case RetrieveBankActor(replyTo) =>
          replyTo ! bankActor
          Behaviors.same
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehaviour, "BankSystem")
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val ec: ExecutionContext = system.executionContext

    val bankActorFuture: Future[ActorRef[Command]] = system.ask(replyTo => RetrieveBankActor(replyTo))
    bankActorFuture.foreach(startHttpServer)
  }

}
