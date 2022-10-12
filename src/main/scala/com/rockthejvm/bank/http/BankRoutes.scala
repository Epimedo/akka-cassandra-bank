package com.rockthejvm.bank.http

import akka.http.scaladsl.server.Directives._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import com.rockthejvm.bank.actors.PersistentBankAccount.Command
import com.rockthejvm.bank.actors.PersistentBankAccount.Response
import com.rockthejvm.bank.actors.PersistentBankAccount.Response._
import com.rockthejvm.bank.actors.PersistentBankAccount.Command._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

case class BankAccountCreationRequest(user: String, currency: String, balance: Double) {

  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
}

case class BankAccountUpdateRequest(currency: String, amount: Double) {

  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

case class FailureResponse(reason: String)

class BankRoutes(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  /*
    POST /bank/
      Payload: bank account request creation as JSON
      Response:
        201 Created
        Location: /bank/uuid

    GET /bank/uuid
      Response:
        200 OK
        JSON representation of the account details

    PUT /bank/
      Payload: (currency, amount) as JSON
      Response:

   */
  val routes = pathPrefix("bank") {
    pathEndOrSingleSlash {
      post {
        entity(as[BankAccountCreationRequest]) { request =>
          /*
            convert request into Command
           */
          onSuccess(createBankAccount(request)) {
            case BankAccountCreatedResponse(id) =>
              respondWithHeader(Location(s"/bank/$id")) {
                complete(StatusCodes.Created)
              }
          }
        }
      }
    } ~
      path(Segment) { id =>
        get {
          /*
                    - Send command to the bank
                    - Expect reply
                    - Send back Http response
                   */
          onSuccess(getBankAccount(id)) {
            case GetBankAccountResponse(Some(account)) =>
              complete(account)
            case GetBankAccountResponse(None) =>
              complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found"))
          }
        } ~
          put {
            entity(as[BankAccountUpdateRequest]) { request =>
              /*
                convert to BankAccountUpdateRequest
                // TODO validate the request
               */
              onSuccess(updateBankAccount(id, request)) {
                case BankAccountBalanceUpdatedResponse(Some(account)) =>
                  complete(account)
                case BankAccountBalanceUpdatedResponse(None) =>
                  complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found"))
              }
            }
          }
      }
  }
}
