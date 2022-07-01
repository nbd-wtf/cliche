# Client for Lightning-compatible Lite Channels aka "Hosted"

This is an amazing piece of software, still work-in-progress, based on [IMMORTAN](https://github.com/fiatjaf/immortan), that can do Lightning things, it is a "lite node", as we may call.

## Running

Grab a jar from the [releases page](https://github.com/fiatjaf/cliche/releases) and run it with `java -jar cliche.jar`. This will use `~/.config/cliche` as your data directory and create a SQLite database there.

To use a different directory do `java -Dcliche.datadir=/my/other/directory -jar cliche.jar`.

In that directory you can have a file called `cliche.conf` that can have the same options that we have specified on [reference.conf](https://github.com/fiatjaf/cliche/blob/master/src/main/resources/reference.conf). The settings will default to the ones on `reference.conf`. You can also specify the settings as flags like the `cliche.datadir` above and these will take precedence even over `cliche.conf`.

### Running the native image (no JVM required)

Download the native image from the [releases page](https://github.com/fiatjaf/cliche/releases), call `chmod +x` on it and run it with `./cliche`. All the rest is the same as above except it doesn't require a Java Virtual Machine at all (also starts much faster and who knows, maybe it's faster and leaner too but that remains to be proven).

To pass options just do `./cliche -Dcliche.datadir=etc`.

## Usage

`cliche` can be used either via direct communication via STDIN with responses via STDOUT or through a websocket (that is hardcoded to listen at `ws://127.0.0.1:12000`). Both interfaces accept either CLI-style commands or JSON-RPC.

This is an example session with some commands called and asynchronous events being received:

```
~> ./cliche
# initial parameters
# configs: network=mainnet json.compact=false
# setting up database
# setting up pathfinder
# instantiating channel master
# instantiating electrum actors
# loading onchain wallets
# start electrum, fee rate listener
# is operational: true
# listening for outgoing payments
# listening for incoming payments
# waiting for commands
{
  "event":"ready"
}
get-info
{
  "id":"",
  "result":{
    "keys":{
      "pub":"0346e3ba6740af014a12536b063bf8815b3609e95d25a8f800e9af0cb0f7ac318e",
      "priv":"7d1ad0b8fd79e24b5e54656ccfb0ccdd4965a9cacc32df0f1b6aae89ff833b31",
      "mnemonics":"vendor doll ritual dune aisle depart trial dinosaur tilt kick stairs forest"
    },
    "block_height":730099,
    "wallets":[{
      "label":"Bitcoin",
      "balance":0
    }],
    "channels":[{
      "id":"0b703fcce268709197a5cfdeee87a9c8aa4bb9ba2982e35e35ebbf4cb0cf469d",
      "balance":10990
    }],
    "known_channels":{
      "normal":78886,
      "hosted":1
    },
    "outgoing_payments":[],
    "fee_rates":{
      "1":2916,
      "10":1162,
      "100":750
    }
  }
}
create-invoice --msatoshi 12000 --description hello
{
  "id":"",
  "result":{
    "invoice":"lnbc120n1p3ys2qgpp5lftl0s00d8y68re249gz86s7mmct4r0vl3p4yhvxwaexs5m3y9dsdqgya6
xs6tnsp5elpt5dklw9wd8l0g8c5xkky6y6jayc3ar5vfdeejeyhvhjdluggqxqy9gcqcqzys9qrsgqrzjqd98kxkpyw0
l9tyy8r8q57k7zpy9zjmh6sez752wj6gcumqnj3yxrxwuy87r6hvpnuqqqqqqqqqqqeqqjqq7wrpaz4dezv92uw7jfgt
5aphq7c5y4rz24756nfzr63wskwhze4af54duph05jh6ycvwadjca5v5y6aucgf697x86fgnj68gmshqkqpuqsenv",
    "msatoshi":12000,
    "payment_hash":"fa57f7c1ef69c9a38f2aa95023ea1edef0ba8decfc43525d867772685371215b",
    "hints_count":1
  }
}
{
  "method":"payment_received",
  "params":{
    "preimage":"51a012f6b18360ed65c3960ebb17ab84120d022768ffb887e82e56463bc729c0",
    "msatoshi":12000,
    "payment_hash":"fa57f7c1ef69c9a38f2aa95023ea1edef0ba8decfc43525d867772685371215b"
  }
}
pay-invoice --invoice lnbc120n1p3ys2znpp5ecudgkjpffs9unvcp2pxws3gjs9sm5u78y340cns330vdtn3req
sdpuve5kzar2v9nr5gpqw35xjueqd9ejqctwypjhsar9wfhxzmpqd9h8vmmfvdjssp5tlzrfn3slaqude9wvl20cn9zm
38auzj8cygpyuplr4g3297skalsxqy9gcqcqzys9qrsgqrzjqtx3k77yrrav9hye7zar2rtqlfkytl094dsp0ms5majz
th6gt7ca6uhdkxl983uywgqqqqlgqqqvx5qqjqrzjqd98kxkpyw0l9tyy8r8q57k7zpy9zjmh6sez752wj6gcumqnj3y
xzhdsmg6qq56utgqqqqqqqqqqqeqqjqurvsq6w7pjse26vyuxta4d9e0w03g2tw3yajks73parppz3dj3f8f73lp7apm
9pduzc7dhaaelqa7rhczz8359trltwt9930jg3z5ccqn3tq4y
{
  "id":"",
  "result":{
    "sent":true,
    "payee":"022c43af2ce577c4b95205cc4a1f3746737eb2cc133bf381d5a165e2c759121c33",
    "fee_reserve":10000,
    "payment_hash":"ce38d45a414a605e4d980a82674228940b0dd39e392357e2708c5ec6ae711e41"
  }
}
{
  "method":"payment_succeeded",
  "params":{
    "payment_hash":"ce38d45a414a605e4d980a82674228940b0dd39e392357e2708c5ec6ae711e41",
    "fee_msatoshi":1,
    "msatoshi":12001,
    "preimage":"0000001124019af4833f82681916f6948e31a4408e53c6427944b4f8dea74670",
    "parts":1
  }
}
```

The same methods can be called either with this CLI-like format or with JSON-RPC, like

```json
{"id":"x","method":"create-invoice","params":{"msatoshi":164000}}
```

## Casual Usage

For casual usage you'll want the CLI-style commands format, so you can just type `create-invoice --msatoshi 100000 --description test`, for example, and get a response in your terminal.

- The STDIN/STDOUT interface works best if you're just running `cliche` in a terminal. In that case you can just use that same terminal window to execute commands.
- The websocket interface works fine too, specially if `cliche` is running as a service or as a subprocess somewhere else, in that case you can use a CLI tool like [`websocat`](https://github.com/vi/websocat), call `websocat ws://127.0.0.1:12000` then start typing commands.

## Programmatic Usage

For programmatic usage you'll want the JSON-RPC format so you can distinguish responses by their JSON-RPC ids.

- The STDIN/STDOUT interface can be used if a different program (i.e. your Lightning-powered application) actually starts `cliche` as a subprocess, then proceeds to write commands to its STDIN and listen to its STDOUT for responses and event notifications. See [go-cliche](https://github.com/fiatjaf/go-cliche) for a library that does that.
- The websocket interface can be used too if `cliche` is running as a standalone service -- for example, by systemd, or inside a container. In that case your application just needs to open a websocket connection to `ws://127.0.0.1` and start sending commands and listening for responses and event notifications.

This is intended to be started by a different program and methods to be called by sending data over STDIN and responses from STDOUT.

## API

### Methods

- `get-info`, params: none
- `request-hc`, params: `pubkey` (string), `host` (string), `port` (number)
- `create-invoice`, params: `msatoshi` (number, optional), `description` (string, optional), `description_hash` (string, optional), `preimage` (string, optional)
- `pay-invoice`, params: `invoice` (string), `msatoshi` (number, optional)
- `check-payment` (works for both incoming and outgoing payments), params: `hash` (string)
- `list-payments`, params: `count` (optional, int)
- `accept-override`, params: `channel-id` (string)

### Notifications

- `ready`
- `payment_succeeded`
- `payment_failed`
- `payment_received`

## Building and development

For development you can just do `sbt run`, and to compile a fat jar that later can be run with just `java -jar` do `sbt assembly`.

If your `build.sbt` happen to have an Immortan version ending with `-SNAPSHOT` that means you either have to build Immortan and publish it locally using that version (`sbt publishLocal` on Immortan repository) or you can change to a non-snapshot version. Pick one from [here](https://repo1.maven.org/maven2/com/fiatjaf/immortan_2.13/).

### Building the GraalVM Native Image

1. Download GraalVM 22.0.0.3, here I used java11-linux version from [release 22.0.0.3](https://github.com/gluonhq/graal/releases/tag/gluon-22.0.0.3-Final);
2. Set the environment variable `GRAALVM_HOME` to the directory where you unzipped that;
3. Run `sbt nativeImage`.

(If something big changes then it might be necessary to run the native image agent again. In that case it might be wise to edit the `Main.scala` block guarded by the `if (Config.nativeImageAgent)` condition to make sure the most important codepaths are touched during the agent run, then run `sbt nativeImageRunAgent` and then `sbt nativeImage`.)

## Uses

This is a list of projects using `cliche`:

  - [@lntxbot](https://github.com/fiatjaf/lntxbot), a Telegram bot that does Lightning tips and payments
  - [LNbits Infinity](https://github.com/lnbits/infinity), a multipurpose extensible web Lightning Wallet provider
  - [relampago](https://github.com/lnbits/relampago), a Golang library for talking to any kind of Lightning backend
