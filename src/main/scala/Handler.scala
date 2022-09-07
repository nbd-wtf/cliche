import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import java.nio.file.{Files, Path, Paths}
import caseapp.core
import caseapp.CaseApp
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.parser.parse
import fs2.concurrent.Topic
import cats.effect._
import io.circe.JsonObject

sealed trait Command
case class UnknownCommand(method: String) extends Command
case class ShowError(err: String) extends Command
case class Ping() extends Command
case class GetInfo() extends Command
case class RequestHostedChannel(
    pubkey: String,
    host: String,
    port: Int,
    label: Option[String],
    secret: Option[String]
) extends Command
case class RemoveHostedChannel(channelId: String) extends Command
case class CreateInvoice(
    description: Option[String],
    description_hash: Option[String],
    msatoshi: Option[Long],
    label: Option[String]
) extends Command
case class PayInvoice(invoice: String, msatoshi: Option[Long]) extends Command
case class PayLnurl(
    lnurl: String,
    msatoshi: Long,
    comment: Option[String],
    name: Option[String],
    attachAuth: Boolean = true
) extends Command
case class CheckPayment(hash: String) extends Command
case class ListPayments(count: Option[Int]) extends Command
case class ListTransactions(count: Option[Int]) extends Command
case class GetAddress() extends Command
case class SendToAddress(address: String, satoshi: Option[Long]) extends Command
case class OpenNormalChannel(
    pubkey: String,
    host: String,
    port: Int,
    satoshi: Option[Long]
) extends Command
case class CloseNormalChannel(channelId: String) extends Command
case class AcceptOverride(channelId: String) extends Command
case class ResizeHostedChannel(channelId: String, satoshiDelta: Long)
    extends Command

object Handler {
  def handle(
      input: String
  )(implicit topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    if (input.trim() == "") IO.unit
    else {
      val command =
        try {
          val c = parse(input).toTry.get.hcursor
          val (id, method, params) = (
            c.get[String]("id").getOrElse(""),
            c.get[String]("method").getOrElse(""),
            c.downField("params")
          )

          method match {
            case "ping"            => (id, params.as[Ping])
            case "get-info"        => (id, params.as[GetInfo])
            case "request-hc"      => (id, params.as[RequestHostedChannel])
            case "create-invoice"  => (id, params.as[CreateInvoice])
            case "pay-invoice"     => (id, params.as[PayInvoice])
            case "pay-lnurl"       => (id, params.as[PayLnurl])
            case "check-payment"   => (id, params.as[CheckPayment])
            case "list-payments"   => (id, params.as[ListPayments])
            case "list-txs"        => (id, params.as[ListTransactions])
            case "remove-hc"       => (id, params.as[RemoveHostedChannel])
            case "accept-override" => (id, params.as[AcceptOverride])
            case "resize-hc"       => (id, params.as[ResizeHostedChannel])
            case "get-address"     => (id, params.as[GetAddress])
            case "send-to-address" => (id, params.as[SendToAddress])
            case "open-nc"         => (id, params.as[OpenNormalChannel])
            case "close-nc"        => (id, params.as[CloseNormalChannel])
            case _                 => (id, UnknownCommand(method))
          }
        } catch {
          case exc
              if exc
                .isInstanceOf[java.lang.ClassCastException] => {
            val spl = input.trim().split(" ")
            val method = spl(0)
            val tail = spl.tail.toIndexedSeq

            (method match {
              case "ping"           => CaseApp.parse[Ping](tail)
              case "get-info"       => CaseApp.parse[GetInfo](tail)
              case "request-hc"     => CaseApp.parse[RequestHostedChannel](tail)
              case "create-invoice" => CaseApp.parse[CreateInvoice](tail)
              case "pay-invoice"    => CaseApp.parse[PayInvoice](tail)
              case "pay-lnurl"      => CaseApp.parse[PayLnurl](tail)
              case "check-payment"  => CaseApp.parse[CheckPayment](tail)
              case "list-payments"  => CaseApp.parse[ListPayments](tail)
              case "list-txs"       => CaseApp.parse[ListTransactions](tail)
              case "remove-hc"      => CaseApp.parse[RemoveHostedChannel](tail)
              case "get-address"    => CaseApp.parse[GetAddress](tail)
              case "send-to-address" => CaseApp.parse[SendToAddress](tail)
              case "open-nc"         => CaseApp.parse[OpenNormalChannel](tail)
              case "close-nc"        => CaseApp.parse[CloseNormalChannel](tail)
              case "accept-override" => CaseApp.parse[AcceptOverride](tail)
              case "resize-hc"       => CaseApp.parse[ResizeHostedChannel](tail)
              case _ => Right(UnknownCommand(method), Seq.empty[String])
            }) match {
              case Left(err)          => ("", ShowError(err.message))
              case Right((params, _)) => ("", params)
            }
          }
          case err: Throwable => {
            ("", ShowError(s"failed to parse: ${err.toString()}"))
          }
        }

      implicit val id = command._1
      command._2 match {
        case params: ShowError =>
          topic.publish1(JSONRPCError(id, params.err)) >> IO.unit
        case params: Ping                 => Commands.ping(params)
        case params: GetInfo              => Commands.getInfo(params)
        case params: RequestHostedChannel => Commands.requestHC(params)
        case params: RemoveHostedChannel  => Commands.removeHC(params)
        case params: CreateInvoice        => Commands.createInvoice(params)
        case params: PayInvoice           => Commands.payInvoice(params)
        case params: PayLnurl             => Commands.payLnurl(params)
        case params: CheckPayment         => Commands.checkPayment(params)
        case params: ListPayments         => Commands.listPayments(params)
        case params: ListTransactions     => Commands.listTransactions(params)
        case params: AcceptOverride       => Commands.acceptOverride(params)
        case params: ResizeHostedChannel  => Commands.resizeHC(params)
        case params: GetAddress           => Commands.getAddress(params)
        case params: SendToAddress        => Commands.sendToAddress(params)
        case params: OpenNormalChannel    => Commands.openNC(params)
        case params: CloseNormalChannel   => Commands.closeNC(params)
        case params: UnknownCommand =>
          topic.publish1(
            JSONRPCError(id, s"unhandled ${params.method}")
          ) >> IO.unit
      }
    }
  }
}
