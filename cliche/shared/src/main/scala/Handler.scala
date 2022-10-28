import java.text.SimpleDateFormat
import java.util.{Calendar, Date}
import java.nio.file.{Files, Path, Paths}
import cats.implicits._
import cats.effect._
import com.monovore.decline._
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.parser.parse
import fs2.concurrent.Topic
import com.monovore.decline._

sealed trait Command
case class UnknownCommand(method: String) extends Command
case class ShowError(err: String) extends Command
case class ShowCLIHelp(h: Help) extends Command
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
case class SendToAddress(address: String, satoshi: Long) extends Command
case class OpenNormalChannel(
    pubkey: String,
    host: String,
    port: Int,
    satoshi: Long
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
      val res = parseJson(input)
        .orElse(parseCLI(input).map(("", _)))
        .getOrElse(("", UnknownCommand(input)))
      id = res._1

      res._2 match {
        case ShowError(err) =>
          topic.publish1(JSONRPCError(id, err)) >> IO.unit
        case ShowCLIHelp(h) =>
          topic.publish1(RawCLIString(h.toString)) >> IO.unit
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

  def parseJson(input: String): Option[(String, Command)] =
    parse(input).toOption.flatMap { j =>
      val c = j.hcursor
      val id = c.get[String]("id").getOrElse("")
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

      data.toOption.map((id, _))
    }

  def parseCLI(input: String): Option[Command] = {
    val cmd = Command(
      "",
      "Call the same methods from the JSON-RPC interface using a CLI syntax"
    ) {
      List(
        Opts.subcommand("ping", "Just check if cliche is alive.") {
          Opts.unit.map(_ => Ping())
        },
        Opts.subcommand(
          "get-info",
          "Get all data about channels, onchain wallets and pending payments."
        ) { Opts.unit.map(_ => GetInfo()) },
        Opts.subcommand(
          "request-hc",
          "get a hosted channel from an external provider."
        ) {
          (
            Opts.option[String](
              long = "pubkey",
              short = "n",
              metavar = "nodeid",
              help = "public key (node id) of the hosted channel provider."
            ),
            Opts.option[String](
              long = "host",
              short = "h",
              metavar = "ip",
              help = "ip address of the hosted channel provider."
            ),
            Opts
              .option[Int](
                long = "port",
                short = "p",
                metavar = "number",
                help =
                  "port where the provider is listening for lightning connections."
              )
              .withDefault(9735),
            Opts
              .option[String](
                long = "label",
                short = "l",
                metavar = "localalias",
                help = "an alias to identify the provider locally."
              )
              .orNone,
            Opts
              .option[String](
                long = "secret",
                short = "s",
                metavar = "hex",
                help =
                  "a secret (i.e. a password), in hex, if that is required by the provider."
              )
              .orNone
          )
            .mapN(RequestHostedChannel.apply)
        },
        Opts.subcommand(
          "create-invoice",
          "create a lightning invoice to receive a payment"
        ) {
          (
            Opts
              .option[String](
                long = "description",
                short = "d",
                metavar = "text",
                help = "short description of the purpose of the payment."
              )
              .orNone,
            Opts
              .option[String](
                long = "description_hash",
                short = "h",
                metavar = "hex",
                help =
                  "sha256 of a longer payment description transmitted through other means (if provided `description` will be ignored)."
              )
              .orNone,
            Opts
              .option[Long](
                long = "msatoshi",
                short = "m",
                metavar = "millisatoshis",
                help = "amount to be paid for this invoice."
              )
              .orNone,
            Opts
              .option[String](
                long = "label",
                short = "l",
                metavar = "text",
                help = "internal label to identify this payment later."
              )
              .orNone
          )
            .mapN(CreateInvoice.apply)
        },
        Opts.subcommand(
          "pay-invoice",
          "pay a lightning invoice."
        ) {
          (
            Opts.option[String](
              long = "invoice",
              short = "i",
              metavar = "bolt11",
              help = "bolt11 payment request to be paid."
            ),
            Opts
              .option[Long](
                long = "msatoshi",
                short = "m",
                metavar = "millisatoshis",
                help = "amount to pay, if not provided on the invoice."
              )
              .orNone
          )
            .mapN(PayInvoice.apply)
        },
        Opts.subcommand(
          "pay-lnurl",
          "pay to an lnurl paycode or lightning address."
        ) {
          (
            Opts.option[String](
              long = "lnurl",
              short = "l",
              metavar = "text",
              help = "lnurl paycode or lightning address."
            ),
            Opts.option[Long](
              long = "msatoshi",
              short = "m",
              metavar = "millisatoshis",
              help = "amount to pay."
            ),
            Opts
              .option[String](
                long = "comment",
                short = "c",
                metavar = "text",
                help = "include a lud-12 comment with the payment"
              )
              .orNone,
            Opts
              .option[String](
                long = "name",
                short = "n",
                metavar = "text",
                help = "include a free lud-18 name parameter with the payment."
              )
              .orNone,
            Opts
              .flag(
                long = "attach-auth",
                short = "a",
                help = "include a lud-18 auth parameter with the payment."
              )
              .orFalse
          )
            .mapN(PayLnurl.apply)
        },
        Opts.subcommand(
          "check-payment",
          "verify the status of an incoming or outgoing payment."
        ) {
          Opts
            .option[String](
              long = "hash",
              short = "h",
              metavar = "hex",
              help = "payment hash of the desired payment."
            )
            .map(CheckPayment.apply)
        },
        Opts.subcommand(
          "list-payments",
          "displays the last lightning payments in any direction."
        ) {
          Opts
            .option[Int](
              long = "count",
              short = "c",
              metavar = "number",
              help = "number of payments to display."
            )
            .orNone
            .map(ListPayments.apply)
        },
        Opts.subcommand(
          "list-txs",
          "displays the last onchain transactions in any direction."
        ) {
          Opts
            .option[Int](
              long = "count",
              short = "c",
              metavar = "number",
              help = "number of payments to display."
            )
            .orNone
            .map(ListTransactions.apply)
        },
        Opts.subcommand(
          "remove-hc",
          "deletes a hosted channel (it won't be deleted on the host side automatically)."
        ) {
          Opts
            .option[String](
              long = "channel-id",
              short = "c",
              metavar = "id",
              help =
                "channel id of the hosted channel that has the override proposal being accepted."
            )
            .map(RemoveHostedChannel.apply)
        },
        Opts.subcommand(
          "accept-override",
          "accept an offered override proposal from the host after an error in a hosted channel."
        ) {
          Opts
            .option[String](
              long = "channel-id",
              short = "c",
              metavar = "id",
              help =
                "channel id of the hosted channel that has the override proposal being accepted."
            )
            .map(AcceptOverride.apply)
        },
        Opts.subcommand(
          "resize-hc",
          "increase (or decrease) the size of a hosted channel (requires the host to agree)."
        ) {
          (
            Opts.option[String](
              long = "channel-id",
              short = "c",
              metavar = "id",
              help =
                "channel id of the hosted channel that has the override proposal being accepted."
            ),
            Opts.option[Long](
              long = "satoshi-delta",
              short = "s",
              metavar = "satoshis",
              help =
                "amount to add (or remove) from the total capacity of the channel."
            )
          ).mapN(ResizeHostedChannel.apply)
        },
        Opts.subcommand(
          "get-address",
          "gets the next unused onchain address."
        ) { Opts.unit.map(_ => GetAddress()) },
        Opts.subcommand("send-to-address", "sends to an onchain address.") {
          (
            Opts.option[String](
              long = "address",
              short = "a",
              metavar = "bitcoin_address",
              help = "the bitcoin address to send a payment to."
            ),
            Opts.option[Long](
              long = "satoshi",
              short = "s",
              metavar = "satoshis",
              help = "the amount in satoshis to send."
            )
          ).mapN(SendToAddress.apply)
        },
        Opts.subcommand("open-nc", "open a normal channel.") {
          (
            Opts.option[String](
              long = "pubkey",
              short = "n",
              metavar = "nodeid",
              help = "public key (node id) of the hosted channel provider."
            ),
            Opts.option[String](
              long = "host",
              short = "h",
              metavar = "ip",
              help = "ip address of the hosted channel provider."
            ),
            Opts
              .option[Int](
                long = "port",
                short = "p",
                metavar = "number",
                help =
                  "port where the provider is listening for lightning connections."
              )
              .withDefault(9735),
            Opts.option[Long](
              long = "satoshi",
              short = "s",
              metavar = "satoshis",
              help = "the amount in satoshis to fund the channel with."
            )
          )
            .mapN(OpenNormalChannel.apply)
        },
        Opts.subcommand("close-nc", "close a normal channel.") {
          (
            Opts
              .option[String](
                long = "channel-id",
                short = "c",
                metavar = "id",
                help =
                  "channel id of the hosted channel that has the override proposal being accepted."
              )
            )
            .map(CloseNormalChannel.apply)
        }
      ).reduce(_.orElse(_))
    }

    Some(
      cmd
        .parse(input.split(" ").toList)
        .left
        .map(ShowCLIHelp(_))
        .merge
    )
  }
}
