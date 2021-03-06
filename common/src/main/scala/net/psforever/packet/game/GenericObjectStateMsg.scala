// Copyright (c) 2017 PSForever
package net.psforever.packet.game

import net.psforever.packet.{GamePacketOpcode, Marshallable, PacketHelpers, PlanetSideGamePacket}
import net.psforever.types.PlanetSideGUID
import scodec.Codec
import scodec.codecs._

/**
  *
  * @param object_guid the target object
  * @param state the state code
  *              16 - open door
  *              17 - close door
  */
final case class GenericObjectStateMsg(object_guid : PlanetSideGUID,
                                       state : Long)
  extends PlanetSideGamePacket {
  type Packet = GenericObjectStateMsg
  def opcode = GamePacketOpcode.GenericObjectStateMsg
  def encode = GenericObjectStateMsg.encode(this)
}

object GenericObjectStateMsg extends Marshallable[GenericObjectStateMsg] {
  implicit val codec : Codec[GenericObjectStateMsg] = (
      ("object_guid" | PlanetSideGUID.codec) ::
        ("state" | uint32L)
    ).as[GenericObjectStateMsg]
}
