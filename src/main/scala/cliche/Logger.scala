package cliche

import akka.actor.Actor
import akka.event.Logging.Debug
import akka.event.Logging.Error
import akka.event.Logging.Info
import akka.event.Logging.InitializeLogger
import akka.event.Logging.LoggerInitialized
import akka.event.Logging.Warning

import java.io._

class UILogger extends Actor {
  val logfile: File = new File("./debug.log")
  val logbuffer: BufferedWriter = new BufferedWriter(new FileWriter(logfile))

  override def preStart() = {
    logbuffer.write("UILogger started")
  }

  override def receive: Receive = {
    case InitializeLogger(_)                        => sender() ! LoggerInitialized
    case Error(cause, logSource, logClass, message) => println(message)
    case Warning(logSource, logClass, message)      => println(message)
    case Info(logSource, logClass, message)         => println("info")
    case Debug(logSource, logClass, message)        => println("debug")
  }

  override def postStop() = {
    logbuffer.write("UILogger stopped")
    logbuffer.close()
  }
}
