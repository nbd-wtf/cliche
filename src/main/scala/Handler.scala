import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import java.nio.file.{Files, Path, Paths}
import org.json4s._
import org.json4s.native.JsonMethods
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL.WithDouble._
import caseapp.core
import caseapp.CaseApp
import fs2.concurrent.Topic
import cats.effect._

sealed trait Command
case class UnknownCommand(method: String) extends Command
case class ShowError(err: caseapp.core.Error) extends Command
case class Ping() extends Command
case class GetInfo() extends Command
case class RequestHostedChannel(pubkey: String, host: String, port: Int)
    extends Command
case class CreateInvoice(
    description: Option[String],
    description_hash: Option[String],
    msatoshi: Option[Long],
    preimage: Option[String],
    label: Option[String]
) extends Command
case class PayInvoice(invoice: String, msatoshi: Option[Long]) extends Command
case class CheckPayment(hash: String) extends Command
case class ListPayments(count: Option[Int]) extends Command
case class RemoveHostedChannel(id: String) extends Command
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

object Handler {
  implicit val formats: Formats = DefaultFormats

  def handle(
      input: String
  )(implicit topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    if (input.trim() == "") IO.unit
    else {
      val command =
        try {
          val parsed: JValue = JsonMethods.parse(input)
          val id =
            (
              try { (parsed \ "id").extract[String] }
              catch { case _: Throwable => "" }
            )
          val method = parsed \ "method"
          val params = parsed \ "params"

          method.extract[String] match {
            case "ping"            => (id, params.extract[Ping])
            case "get-info"        => (id, params.extract[GetInfo])
            case "request-hc"      => (id, params.extract[RequestHostedChannel])
            case "create-invoice"  => (id, params.extract[CreateInvoice])
            case "pay-invoice"     => (id, params.extract[PayInvoice])
            case "check-payment"   => (id, params.extract[CheckPayment])
            case "list-payments"   => (id, params.extract[ListPayments])
            case "remove-hc"       => (id, params.extract[RemoveHostedChannel])
            case "accept-override" => (id, params.extract[AcceptOverride])
            case "get-address"     => (id, params.extract[GetAddress])
            case "send-to-address" => (id, params.extract[SendToAddress])
            case "open-nc"         => (id, params.extract[OpenNormalChannel])
            case "close-nc"        => (id, params.extract[CloseNormalChannel])
            case _                 => (id, params.extract[UnknownCommand])
          }
        } catch {
          case _: Throwable => {
            val spl = input.trim().split(" ")
            val method = spl(0)
            val tail = spl.tail.toIndexedSeq

            val res = method match {
              case "ping"           => CaseApp.parse[Ping](tail)
              case "get-info"       => CaseApp.parse[GetInfo](tail)
              case "request-hc"     => CaseApp.parse[RequestHostedChannel](tail)
              case "create-invoice" => CaseApp.parse[CreateInvoice](tail)
              case "pay-invoice"    => CaseApp.parse[PayInvoice](tail)
              case "check-payment"  => CaseApp.parse[CheckPayment](tail)
              case "list-payments"  => CaseApp.parse[ListPayments](tail)
              case "remove-hc"      => CaseApp.parse[RemoveHostedChannel](tail)
              case "get-address"    => CaseApp.parse[GetAddress](tail)
              case "send-to-address" => CaseApp.parse[SendToAddress](tail)
              case "open-nc"         => CaseApp.parse[OpenNormalChannel](tail)
              case "close-nc"        => CaseApp.parse[CloseNormalChannel](tail)
              case "accept-override" => CaseApp.parse[AcceptOverride](tail)
              case _ => Right(UnknownCommand(method), Seq.empty[String])
            }
            res match {
              case Left(err)       => ("", ShowError(err))
              case Right((cmd, _)) => ("", cmd)
            }
          }
        }

      implicit val id = command._1
      command._2 match {
        case params: ShowError =>
          topic.publish1(JSONRPCError(id, params.err.message)) >> IO.unit
        case _: Ping                      => Commands.ping()
        case _: GetInfo                   => Commands.getInfo()
        case params: RequestHostedChannel => Commands.requestHC(params)
        case params: CreateInvoice        => Commands.createInvoice(params)
        case params: PayInvoice           => Commands.payInvoice(params)
        case params: CheckPayment         => Commands.checkPayment(params)
        case params: ListPayments         => Commands.listPayments(params)
        case params: RemoveHostedChannel  => Commands.removeHC(params)
        case params: AcceptOverride       => Commands.acceptOverride(params)
        case _: GetAddress                => Commands.getAddress()
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
