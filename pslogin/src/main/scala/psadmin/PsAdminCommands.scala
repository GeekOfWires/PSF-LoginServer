// Copyright (c) 2020 PSForever
package net.psforever.psadmin

import scala.collection.mutable.Map

sealed trait CommandResponse
case class CommandGoodResponse(message : String, data : Map[String,Any]) extends CommandResponse
case class CommandErrorResponse(message : String, data : Map[String,Any]) extends CommandResponse

object PsAdminCommands {
  import CmdInternal._

  val commands : Map[String,CommandInfo] = Map(
    "list_players" -> Command("""Return a list of players connected to the interstellar cluster.""", classOf[CmdListPlayers]),
    "dump_config" -> CommandInternal("""Dumps entire running config.""", cmdDumpConfig),
    "shutdown" -> Command("""Shuts down the server forcefully.""", classOf[CmdShutdown]),
    "thread_dump" -> CommandInternal("""Returns all thread's stack traces.""", cmdThreadDump)
  )

  sealed trait CommandInfo {
    def usage: String
  }

  /// A command with full access to the ActorSystem and WorldServer services.
  /// Spawns an Actor to handle the request and the service queries
  case class Command[T](usage : String, handler : Class[T]) extends CommandInfo

  /// A command without access to the ActorSystem or any services
  case class CommandInternal(usage : String, handler : ((Array[String]) => CommandResponse)) extends CommandInfo
}

