import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import java.nio.file.{Files, Path, Paths}
import org.json4s.native.JsonMethods
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL.WithDouble._
import caseapp.core
import caseapp.CaseApp

import Commands._

object Handler {
  val commandLog: scala.collection.mutable.Map[Long, String] =
    scala.collection.mutable.Map.empty

  def writeCommandLog(): Unit = Files.write(
    Paths.get(s"${Config.datadir}/command.log"),
    commandLog
      .map[Tuple2[Long, String]](kv => kv)
      .toList
      .sortBy[Long]({ case (time, _) => time })
      .map({
        case (time, text) => {
          val formattedDate =
            (new SimpleDateFormat("d MMM yyyy HH:mm:ss Z"))
              .format(new Date(time))
          s"[$formattedDate] ${text}"
        }
      })
      .mkString("\n")
      .getBytes()
  )

  def handle(input: String): String = {
    val now = Calendar.getInstance().getTime().getTime()

    commandLog(now) = input
    def updateLog(suffix: String): Unit = {
      commandLog.updateWith(now)(pre => pre.map(log => s"$log $suffix"))
    }
    writeCommandLog()

    val (id: String, command: Command) =
      try {
        val parsed: JValue = JsonMethods.parse(input)
        val id =
          (
            try { (parsed \ "id").extract[String] }
            catch { case _: Throwable => "" }
          )
        val method = parsed \ "method"
        val params = parsed \ "params"

        updateLog(method.extract[String])

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

          updateLog(method)

          val res = method match {
            case "ping"            => CaseApp.parse[Ping](tail)
            case "get-info"        => CaseApp.parse[GetInfo](tail)
            case "request-hc"      => CaseApp.parse[RequestHostedChannel](tail)
            case "create-invoice"  => CaseApp.parse[CreateInvoice](tail)
            case "pay-invoice"     => CaseApp.parse[PayInvoice](tail)
            case "check-payment"   => CaseApp.parse[CheckPayment](tail)
            case "list-payments"   => CaseApp.parse[ListPayments](tail)
            case "remove-hc"       => CaseApp.parse[RemoveHostedChannel](tail)
            case "accept-override" => CaseApp.parse[AcceptOverride](tail)
            case _ => Right(UnknownCommand(method), Seq.empty[String])
          }
          res match {
            case Left(err)       => ("", ShowError(err))
            case Right((cmd, _)) => ("", cmd)
          }
        }
      }

    writeCommandLog()

    val response = command match {
      case params: ShowError            => Left(params.err.message)
      case _: Ping                      => ping()
      case _: GetInfo                   => getInfo()
      case params: RequestHostedChannel => requestHC(params)
      case params: CreateInvoice        => createInvoice(params)
      case params: PayInvoice           => payInvoice(params)
      case params: CheckPayment         => checkPayment(params)
      case params: ListPayments         => listPayments(params)
      case params: RemoveHostedChannel  => removeHC(params)
      case params: AcceptOverride       => acceptOverride(params)
      case params: UnknownCommand       => Left(s"unhandled ${params.method}")
    }

    val res = response match {
      case Left(err) => {
        updateLog(s"error: $err")

        renderjson(
          // @formatter:off
          ("id" -> id) ~~
          ("error" ->
            (("message" -> err) ~~
             ("code" -> 1))
          )
          // @formatter:on
        )
      }
      case Right(result) => {
        updateLog("success")
        renderjson(("id" -> id) ~~ ("result" -> result))
      }
    }

    // filter out commands older than 10 minutes
    commandLog.filterInPlace((time, _) =>
      time > Calendar.getInstance().getTime().getTime() - 10 * 60 * 1000
    )
    writeCommandLog()

    res
  }
}
