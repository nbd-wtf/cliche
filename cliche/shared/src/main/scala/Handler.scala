import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import java.nio.file.{Files, Path, Paths}
import cats.effect._
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.parser.parse
import fs2.concurrent.Topic
import com.monovore.decline._

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
    implicit var id: String = ""

    if (input.trim() == "") IO.unit
    else {
      val c = parse(input).toTry.get.hcursor
      id = c.get[String]("id").getOrElse("")
      val method = c.get[String]("method").getOrElse("")
      val params = c.downField("params")

      val data: Decoder.Result[Command] = method match {
        case "ping"            => params.as[Ping]
        case "get-info"        => params.as[GetInfo]
        case "request-hc"      => params.as[RequestHostedChannel]
        case "create-invoice"  => params.as[CreateInvoice]
        case "pay-invoice"     => params.as[PayInvoice]
        case "pay-lnurl"       => params.as[PayLnurl]
        case "check-payment"   => params.as[CheckPayment]
        case "list-payments"   => params.as[ListPayments]
        case "list-txs"        => params.as[ListTransactions]
        case "remove-hc"       => params.as[RemoveHostedChannel]
        case "accept-override" => params.as[AcceptOverride]
        case "resize-hc"       => params.as[ResizeHostedChannel]
        case "get-address"     => params.as[GetAddress]
        case "send-to-address" => params.as[SendToAddress]
        case "open-nc"         => params.as[OpenNormalChannel]
        case "close-nc"        => params.as[CloseNormalChannel]
        case _                 => Right(UnknownCommand(method))
      }

      data.toTry.get match {
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
