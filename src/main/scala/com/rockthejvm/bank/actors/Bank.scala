package com.rockthejvm.bank.actors

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import com.rockthejvm.bank.actors.PersistentBankAccount.Command.{CreateBankAccount, GetBankAccount}
import com.rockthejvm.bank.actors.PersistentBankAccount.Response.{BankAccountCreatedResponse, GetBankAccountResponse}

import scala.concurrent.duration._
import java.util.UUID
import scala.concurrent.ExecutionContext

object Bank {

  // commands = messages

  import PersistentBankAccount.Command._
  import PersistentBankAccount.Command
  import PersistentBankAccount.Response._

  // events
  sealed trait Event

  case class BankAccountCreated(id: String) extends Event

  // states
  case class State(accounts: Map[String, ActorRef[Command]])


  // commandHandler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand@CreateBankAccount(_, _, _, _) =>
        val id = UUID.randomUUID().toString
        val newBankAccount = context.spawn(PersistentBankAccount(id), id)
        Effect
          .persist(BankAccountCreated(id))
          .thenReply(newBankAccount)(_ => createCommand)
      case updateCommand@UpdateBalance(id, _, _, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(updateCommand)
          case None =>
            Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
        }
      case getCmd@GetBankAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(getCmd)
          case None =>
            Effect.reply(replyTo)(GetBankAccountResponse(None))
        }
    }

  // eventHandler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case BankAccountCreated(id) =>
        val account = context.child(id)
          .getOrElse(context.spawn(PersistentBankAccount(id), id))
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account))
    }


  // behaviour
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("bank"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }

}

object BankPlayground {

  def main(args: Array[String]): Unit = {
    import PersistentBankAccount.Response
    val rootBehaviour: Behavior[NotUsed] = Behaviors.setup { context =>
      val bank = context.spawn(Bank(), "bank")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case BankAccountCreatedResponse(id) =>
          logger.info(s"Successfully created account $id")
          Behaviors.same
        case GetBankAccountResponse(maybeBankAccount) =>
          logger.info(s"Account details $maybeBankAccount")
          Behaviors.same
      }, "replyHandler")
      // ask pattern
      import akka.actor.typed.scaladsl.AskPattern._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ex: ExecutionContext = context.executionContext

//      bank ! CreateBankAccount("daniel", "USD", 10, responseHandler)
      bank ! GetBankAccount("25754ec9-b3ee-4b81-8e96-50edd884eefe", responseHandler)

      Behaviors.empty
    }

    val actorSystem = ActorSystem(rootBehaviour, "BankDemo")
  }
}
