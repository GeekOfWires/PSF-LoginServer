// Copyright (c) 2016 PSForever.net to present
package net.psforever.packet.game

import net.psforever.packet.game.objectcreate.{ConstructorData, ObjectClass}
import net.psforever.packet.{GamePacketOpcode, Marshallable, PacketHelpers, PlanetSideGamePacket}
import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.codecs._
import shapeless.{::, HNil}

/**
  * The parent information of a created object.<br>
  * <br>
  * Rather than a created-parent with a created-child relationship, the whole of the packet still only creates the child.
  * The parent is a pre-existing object into which the (created) child is attached.<br>
  * <br>
  * The slot is encoded as a string length integer commonly used by PlanetSide.
  * It is either a 0-127 eight bit number (0 = 0x80), or a 128-32767 sixteen bit number (128 = 0x0080).
  * @param guid the GUID of the parent object
  * @param slot a parent-defined slot identifier that explains where the child is to be attached to the parent
  */
case class ObjectCreateMessageParent(guid : PlanetSideGUID,
                                     slot : Int)

/**
  * Communicate with the client that a certain object with certain properties is to be created.
  * The object may also have primitive assignment (attachment) properties.<br>
  * <br>
  * In normal packet data order, the parent object is specified before the actual object is specified.
  * This is most likely a method of early correction.
  * "Does this parent object exist?"
  * "Is this new object something that can be attached to this parent?"
  * "Does the parent have the appropriate attachment slot?"
  * There is no fail-safe method for any of these circumstances being false, however, and the object will simply not be created.
  * In instance where the parent data does not exist, the object-specific data is immediately encountered.<br>
  * <br>
  * The object's GUID is assigned by the server.
  * The clients are required to adhere to this new GUID referring to the object.
  * There is no fail-safe for a conflict between what the server thinks is a new GUID and what any client thinks is an already-assigned GUID.
  * Likewise, there is no fail-safe between a client failing or refusing to create an object and the server thinking an object has been created.
  * (The GM-level command `/sync` tests for objects that "do not match" between the server and the client.
  * It's implementation and scope are undefined.)<br>
  * <br>
  * Knowing the object's class is essential for parsing the specific information passed by the `data` parameter.
  * @param streamLength the total length of the data that composes this packet in bits, excluding the opcode and end padding
  * @param objectClass the code for the type of object being constructed
  * @param guid the GUID this object will be assigned
  * @param parentInfo if defined, the relationship between this object and another object (its parent)
  * @param data  if defined, the data used to construct this type of object
  */
case class ObjectCreateMessage(streamLength : Long,
                               objectClass : Int,
                               guid : PlanetSideGUID,
                               parentInfo : Option[ObjectCreateMessageParent],
                               data : Option[ConstructorData])
  extends PlanetSideGamePacket {
  def opcode = GamePacketOpcode.ObjectCreateMessage
  def encode = ObjectCreateMessage.encode(this)
}

object ObjectCreateMessage extends Marshallable[ObjectCreateMessage] {
  type Pattern = Int :: PlanetSideGUID :: Option[ObjectCreateMessageParent] :: HNil
  type outPattern = Long :: Int :: PlanetSideGUID :: Option[ObjectCreateMessageParent] :: Option[ConstructorData] :: HNil
  /**
    * Codec for formatting around the lack of parent data in the stream.
    */
  private val noParent : Codec[Pattern] = (
    ("objectClass" | uintL(0xb)) :: //11u
      ("guid" | PlanetSideGUID.codec) //16u
    ).xmap[Pattern](
    {
      case cls :: guid :: HNil =>
        cls :: guid :: None :: HNil
    }, {
      case cls :: guid :: None :: HNil =>
        cls :: guid :: HNil
    }
  )
  /**
    * Codec for reading and formatting parent data from the stream.
    */
  private val parent : Codec[Pattern] = (
    ("parentGuid" | PlanetSideGUID.codec) :: //16u
      ("objectClass" | uintL(0xb)) :: //11u
      ("guid" | PlanetSideGUID.codec) :: //16u
      ("parentSlotIndex" | PacketHelpers.encodedStringSize) //8u or 16u
    ).xmap[Pattern](
    {
      case pguid :: cls :: guid :: slot :: HNil =>
        cls :: guid :: Some(ObjectCreateMessageParent(pguid, slot)) :: HNil
    }, {
      case cls :: guid :: Some(ObjectCreateMessageParent(pguid, slot)) :: HNil =>
        pguid :: cls :: guid :: slot :: HNil
    }
  )

  /**
    * Take bit data and transform it into an object that expresses the important information of a game piece.<br>
    * <br>
    * This function is fail-safe because it catches errors involving bad parsing of the bitstream data.
    * Generally, the `Exception` messages themselves are not useful.
    * The important parts are what the packet thought the object class should be and what it actually processed.
    * The bit data that failed to parse is retained for debugging at a later time.
    * @param objectClass the code for the type of object being constructed
    * @param data        the bitstream data
    * @return the optional constructed object
    */
  private def decodeData(objectClass : Int, data : BitVector) : Option[ConstructorData] = {
    var out : Option[ConstructorData] = None
    val copy = data.drop(0)
    try {
      val outOpt : Option[DecodeResult[_]] = ObjectClass.selectDataCodec(objectClass).decode(copy).toOption
      if(outOpt.isDefined)
        out = outOpt.get.value.asInstanceOf[ConstructorData.genericPattern]
    }
    catch {
      case ex : Exception =>
        //catch and release, any sort of parse error
    }
    out
  }

  /**
    * Take the important information of a game piece and transform it into bit data.<br>
    * <br>
    * This function is fail-safe because it catches errors involving bad parsing of the object data.
    * Generally, the `Exception` messages themselves are not useful.
    * If parsing fails, all data pertinent to debugging the failure is retained in the constructor.
    * @param objClass the code for the type of object being deconstructed
    * @param obj      the object data
    * @return the bitstream data
    */
  private def encodeData(objClass : Int, obj : ConstructorData) : BitVector = {
    var out = BitVector.empty
    try {
      val outOpt : Option[BitVector] = ObjectClass.selectDataCodec(objClass).encode(Some(obj.asInstanceOf[ConstructorData])).toOption
      if(outOpt.isDefined)
        out = outOpt.get
    }
    catch {
      case ex : Exception =>
        //catch and release, any sort of parse error
    }
    out
  }

  /**
    * Calculate the stream length in number of bits by factoring in the whole message in two portions.
    * @param parentInfo if defined, information about the parent
    * @param data       the data length is indeterminate until it is read
    * @return the total length of the stream in bits
    */
  private def streamLen(parentInfo : Option[ObjectCreateMessageParent], data : BitVector) : Long = {
    //knowable length
    val first : Long = commonMsgLen(parentInfo)
    //data length
    var second : Long = data.size
    val secondMod4 : Long = second % 4L
    if(secondMod4 > 0L) {
      //pad to include last whole nibble
      second += 4L - secondMod4
    }
    first + second
  }

  /**
    * Calculate the stream length in number of bits by factoring in the whole message in two portions.
    * @param parentInfo if defined, information about the parent
    * @param data       the data length is indeterminate until it is read
    * @return the total length of the stream in bits
    */
  private def streamLen(parentInfo : Option[ObjectCreateMessageParent], data : ConstructorData) : Long = {
    //knowable length
    val first : Long = commonMsgLen(parentInfo)
    //data length
    var second : Long = data.bitsize
    val secondMod4 : Long = second % 4L
    if(secondMod4 > 0L) {
      //pad to include last whole nibble
      second += 4L - secondMod4
    }
    first + second
  }

  /**
    * Calculate the length (in number of bits) of the basic packet message region.<br>
    * <br>
    * Ignoring the parent data, constant field lengths have already been factored into the results.
    * That includes:
    * the length of the stream length field (32u),
    * the object's class (11u),
    * the object's GUID (16u),
    * and the bit to determine if there will be parent data.
    * In total, these fields form a known fixed length of 60u.
    * @param parentInfo if defined, the parentInfo adds either 24u or 32u
    * @return the length, including the optional parent data
    */
  private def commonMsgLen(parentInfo : Option[ObjectCreateMessageParent]) : Long = {
    if(parentInfo.isDefined) {
      //(32u + 1u + 11u + 16u) ?+ (16u + (8u | 16u))
      if(parentInfo.get.slot > 127) 92L else 84L
    }
    else {
      60L
    }
  }

  implicit val codec : Codec[ObjectCreateMessage] = (
    ("streamLength" | uint32L) ::
      (either(bool, parent, noParent).exmap[Pattern] (
        {
          case Left(a :: b :: Some(c) :: HNil) =>
            Attempt.successful(a :: b :: Some(c) :: HNil) //true, _, _, Some(c)
          case Right(a :: b :: None :: HNil) =>
            Attempt.successful(a :: b :: None :: HNil) //false, _, _, None
          // failure cases
          case Left(a :: b :: None :: HNil) =>
            Attempt.failure(Err("missing parent structure")) //true, _, _, None
          case Right(a :: b :: Some(c) :: HNil) =>
            Attempt.failure(Err("unexpected parent structure")) //false, _, _, Some(c)
        }, {
          case a :: b :: Some(c) :: HNil =>
            Attempt.successful(Left(a :: b :: Some(c) :: HNil))
          case a :: b :: None :: HNil =>
            Attempt.successful(Right(a :: b :: None :: HNil))
        }
      ) :+
        ("data" | bits)) //greed is good
    ).xmap[outPattern](
    {
      case len :: cls :: guid :: par :: data :: HNil =>
        len :: cls :: guid :: par :: decodeData(cls, data) :: HNil
    }, {
      case _ :: cls :: guid :: par :: Some(obj) :: HNil =>
        streamLen(par, obj) :: cls :: guid :: par :: encodeData(cls, obj) :: HNil
      case _ :: cls :: guid :: par :: None :: HNil =>
        streamLen(par, BitVector.empty) :: cls :: guid :: par :: BitVector.empty :: HNil
    }
  ).exmap[ObjectCreateMessage](
    {
      case len :: cls :: guid :: par :: obj :: HNil =>
        Attempt.successful(ObjectCreateMessage(len, cls, guid, par, obj))
    }, {
      case ObjectCreateMessage(_, _, _, _, None) =>
        Attempt.failure(Err("no object to encode"))
      case ObjectCreateMessage(len, cls, guid, par, obj) =>
        Attempt.successful(len :: cls :: guid :: par :: obj :: HNil)
    }
  ).as[ObjectCreateMessage]
}