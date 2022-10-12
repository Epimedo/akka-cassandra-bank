package com.rockthejvm.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object PersistentBankAccount {

  /*
    - fault tolerance
    - auditing
   */

  sealed trait Command

  // commands = messages
  object Command {

    case class CreateBankAccount(user: String, currency: String, initialBalance: Double, replyTo: ActorRef[Response]) extends Command

    case class UpdateBalance(id: String, currency: String, amount: Double, replyTo: ActorRef[Response]) extends Command

    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command

  }

  // events = to persists to Cassandra
  trait Event

  case class BankAccountCreated(bank: BankAccount) extends Event

  case class BalanceUpdate(amount: Double) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)


  // responses
  sealed trait Response

  object Response {
    case class BankAccountCreatedResponse(id: String) extends Response

    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response

    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response

  }

  // command handler = message handlers => persist an event
  // event handler => update state
  // state

  import Command._
  import Response._

  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) => command match {
    case CreateBankAccount(user, currency, initialBalance, bank) =>
      val id = state.id
      Effect
        .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance)))
        .thenReply(bank)(_ => BankAccountCreatedResponse(id))
    case UpdateBalance(_, _, amount, bank) =>
      val newBalance = state.balance + amount
      if (newBalance < 0)
        Effect.reply(bank)(BankAccountBalanceUpdatedResponse(None))
      else
        Effect
          .persist(BalanceUpdate(newBalance))
          .thenReply(bank)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))
    case GetBankAccount(_, bank) =>
      Effect.reply(bank)(GetBankAccountResponse(Some(state)))
  }
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) =>
        bankAccount
      case BalanceUpdate(amount) =>
        state.copy(balance = amount)
    }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
