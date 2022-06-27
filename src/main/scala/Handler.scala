import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import java.nio.file.{Files, Path, Paths}
import org.json4s.native.JsonMethods
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL.WithDouble._
import caseapp.core
import caseapp.CaseApp
import fs2.concurrent.Topic
import cats.effect._

import Commands._

object Handler {
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
        case _: Ping                      => ping()
        case _: GetInfo                   => getInfo()
        case params: RequestHostedChannel => requestHC(params)
        case params: CreateInvoice        => createInvoice(params)
        case params: PayInvoice           => payInvoice(params)
        case params: CheckPayment         => checkPayment(params)
        case params: ListPayments         => listPayments(params)
        case params: RemoveHostedChannel  => removeHC(params)
        case params: AcceptOverride       => acceptOverride(params)
        case params: UnknownCommand =>
          topic.publish1(
            JSONRPCError(id, s"unhandled ${params.method}")
          ) >> IO.unit
      }
    }
  }
}
