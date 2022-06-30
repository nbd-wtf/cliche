import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import java.nio.file.{Files, Path, Paths}
import spray.json._
import caseapp.core
import caseapp.CaseApp
import fs2.concurrent.Topic
import cats.effect._

sealed trait Command
case class UnknownCommand(method: String) extends Command
case class ShowError(err: String) extends Command
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
  import SprayConverters._

  def handle(
      input: String
  )(implicit topic: Topic[IO, JSONRPCMessage]): IO[Unit] = {
    if (input.trim() == "") IO.unit
    else {
      val command =
        try {
          val parsed = JsonParser(input).asInstanceOf[JsObject]
          val (id, method, params) = (
            parsed.fields
              .get("id")
              .map(_.asInstanceOf[JsString].value)
              .getOrElse(""),
            parsed.fields
              .get("method")
              .map(_.asInstanceOf[JsString].value)
              .getOrElse(""),
            parsed.fields.get("params").getOrElse(JsObject.empty)
          )

          method match {
            case "ping"       => (id, params.convertTo[Ping])
            case "get-info"   => (id, params.convertTo[GetInfo])
            case "request-hc" => (id, params.convertTo[RequestHostedChannel])
            case "create-invoice" => (id, params.convertTo[CreateInvoice])
            case "pay-invoice"    => (id, params.convertTo[PayInvoice])
            case "check-payment"  => (id, params.convertTo[CheckPayment])
            case "list-payments"  => (id, params.convertTo[ListPayments])
            case "remove-hc"      => (id, params.convertTo[RemoveHostedChannel])
            case "accept-override" => (id, params.convertTo[AcceptOverride])
            case "get-address"     => (id, params.convertTo[GetAddress])
            case "send-to-address" => (id, params.convertTo[SendToAddress])
            case "open-nc"         => (id, params.convertTo[OpenNormalChannel])
            case "close-nc"        => (id, params.convertTo[CloseNormalChannel])
            case _                 => (id, UnknownCommand(method))
          }
        } catch {
          case exc
              if exc.isInstanceOf[spray.json.JsonParser.ParsingException] || exc
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
              case "check-payment"  => CaseApp.parse[CheckPayment](tail)
              case "list-payments"  => CaseApp.parse[ListPayments](tail)
              case "remove-hc"      => CaseApp.parse[RemoveHostedChannel](tail)
              case "get-address"    => CaseApp.parse[GetAddress](tail)
              case "send-to-address" => CaseApp.parse[SendToAddress](tail)
              case "open-nc"         => CaseApp.parse[OpenNormalChannel](tail)
              case "close-nc"        => CaseApp.parse[CloseNormalChannel](tail)
              case "accept-override" => CaseApp.parse[AcceptOverride](tail)
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
        case params: CreateInvoice        => Commands.createInvoice(params)
        case params: PayInvoice           => Commands.payInvoice(params)
        case params: CheckPayment         => Commands.checkPayment(params)
        case params: ListPayments         => Commands.listPayments(params)
        case params: RemoveHostedChannel  => Commands.removeHC(params)
        case params: AcceptOverride       => Commands.acceptOverride(params)
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

object SprayConverters extends DefaultJsonProtocol {
  implicit val convertPing: JsonFormat[Ping] =
    jsonFormat0(Ping.apply)
  implicit val convertGetInfo: JsonFormat[GetInfo] =
    jsonFormat0(GetInfo.apply)
  implicit val convertRequestHostedChannel: JsonFormat[RequestHostedChannel] =
    jsonFormat3(RequestHostedChannel.apply)
  implicit val convertCreateInvoice: JsonFormat[CreateInvoice] =
    jsonFormat5(CreateInvoice.apply)
  implicit val convertPayInvoice: JsonFormat[PayInvoice] =
    jsonFormat2(PayInvoice.apply)
  implicit val convertCheckPayment: JsonFormat[CheckPayment] =
    jsonFormat1(CheckPayment.apply)
  implicit val convertListPayments: JsonFormat[ListPayments] =
    jsonFormat1(ListPayments.apply)
  implicit val convertRemoveHostedChannel: JsonFormat[RemoveHostedChannel] =
    jsonFormat1(RemoveHostedChannel.apply)
  implicit val convertGetAddress: JsonFormat[GetAddress] =
    jsonFormat0(GetAddress.apply)
  implicit val convertSendToAddress: JsonFormat[SendToAddress] =
    jsonFormat2(SendToAddress.apply)
  implicit val convertOpenNormalChannel: JsonFormat[OpenNormalChannel] =
    jsonFormat4(OpenNormalChannel.apply)
  implicit val convertCloseNormalChannel: JsonFormat[CloseNormalChannel] =
    jsonFormat1(CloseNormalChannel.apply)
  implicit val convertAcceptOverride: JsonFormat[AcceptOverride] =
    jsonFormat1(AcceptOverride.apply)
}
