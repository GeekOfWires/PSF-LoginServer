// Copyright (c) 2020 PSForever
import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import net.psforever.objects.{AmmoBox, GlobalDefinitions, Player, Tool}
import net.psforever.objects.equipment.{Ammo, Equipment}
import net.psforever.objects.guid.{GUIDTask, Task, TaskResolver}
import net.psforever.objects.inventory.{Container, InventoryItem}
import net.psforever.objects.serverobject.PlanetSideServerObject
import net.psforever.objects.serverobject.containable.Containable
import net.psforever.objects.zones.Zone
import net.psforever.packet.game.ObjectHeldMessage
import net.psforever.types.{PlanetSideGUID, TransactionType, Vector3}
import services.Service
import services.avatar.{AvatarAction, AvatarServiceMessage}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.implicitConversions

object WorldSession {
  /**
    * Convert a boolean value into an integer value.
    * Use: `true:Int` or `false:Int`
    * @param b `true` or `false` (or `null`)
    * @return 1 for `true`; 0 for `false`
    */
  implicit def boolToInt(b : Boolean) : Int = if(b) 1 else 0
  private implicit val timeout = new Timeout(5000 milliseconds)

  /**
    * Use this for placing equipment that has yet to be registered into a container,
    * such as in support of changing ammunition types in `Tool` objects (weapons).
    * If the object can not be placed into the container, it will be dropped onto the ground.
    * It will also be dropped if it takes too long to be placed.
    * Item swapping during the placement is not allowed.
    * @see `ask`
    * @see `ChangeAmmoMessage`
    * @see `Containable.CanNotPutItemInSlot`
    * @see `Containable.PutItemAway`
    * @see `Future.onComplete`
    * @see `Future.recover`
    * @see `tell`
    * @see `Zone.Ground.DropItem`
    * @param obj the container
    * @param item the item being manipulated
    * @return a `Future` that anticipates the resolution to this manipulation
    */
  def PutEquipmentInInventoryOrDrop(obj : PlanetSideServerObject with Container)(item : Equipment) : Future[Any] = {
    val localContainer = obj
    val localItem = item
    val result = ask(localContainer.Actor, Containable.PutItemAway(localItem))
    result.onComplete {
      case scala.util.Failure(_) | scala.util.Success(_ : Containable.CanNotPutItemInSlot) =>
        localContainer.Zone.Ground.tell(Zone.Ground.DropItem(localItem, localContainer.Position, Vector3.z(localContainer.Orientation.z)), localContainer.Actor)
      case _ => ;
    }
    result
  }

  /**
    * Use this for placing equipment that has yet to be registered into a container,
    * such as in support of changing ammunition types in `Tool` objects (weapons).
    * Equipment will go wherever it fits in containing object, or be dropped if it fits nowhere.
    * Item swapping during the placement is not allowed.
    * @see `ChangeAmmoMessage`
    * @see `GUIDTask.RegisterEquipment`
    * @see `PutEquipmentInInventoryOrDrop`
    * @see `Task`
    * @see `TaskResolver.GiveTask`
    * @param obj the container
    * @param item the item being manipulated
    * @return a `TaskResolver` object
    */
  def PutNewEquipmentInInventoryOrDrop(obj : PlanetSideServerObject with Container)(item : Equipment) : TaskResolver.GiveTask = {
    val localZone = obj.Zone
    TaskResolver.GiveTask(
      new Task() {
        private val localContainer = obj
        private val localItem = item

        override def isComplete : Task.Resolution.Value = Task.Resolution.Success

        def Execute(resolver : ActorRef) : Unit = {
          PutEquipmentInInventoryOrDrop(localContainer)(localItem)
          resolver ! scala.util.Success(this)
        }
      },
      List(GUIDTask.RegisterEquipment(item)(localZone.GUID))
    )
  }

  /**
    * Use this for obtaining new equipment from a loadout specification.
    * The loadout specification contains a specific slot position for placing the item.
    * This request will (probably) be  coincidental with a number of other such requests based on that loadout
    * so items must be rigidly placed else cascade into a chaostic order.
    * Item swapping during the placement is not allowed.
    * @see `ask`
    * @see `AvatarAction.ObjectDelete`
    * @see `ChangeAmmoMessage`
    * @see `Containable.CanNotPutItemInSlot`
    * @see `Containable.PutItemAway`
    * @see `Future.onComplete`
    * @see `Future.recover`
    * @see `GUIDTask.UnregisterEquipment`
    * @see `tell`
    * @see `Zone.AvatarEvents`
    * @param obj the container
    * @param taskResolver na
    * @param item the item being manipulated
    * @param slot na
    * @return a `Future` that anticipates the resolution to this manipulation
    */
  def PutEquipmentInInventorySlot(obj : PlanetSideServerObject with Container, taskResolver : ActorRef)(item : Equipment, slot : Int) : Future[Any] = {
    val localContainer = obj
    val localItem = item
    val localResolver = taskResolver
    val result = ask(localContainer.Actor, Containable.PutItemInSlotOnly(localItem, slot))
    result.onComplete {
      case scala.util.Failure(_) | scala.util.Success(_ : Containable.CanNotPutItemInSlot) =>
        localResolver ! GUIDTask.UnregisterEquipment(localItem)(localContainer.Zone.GUID)
      case _ => ;
    }
    result
  }

  /**
    * Use this for obtaining new equipment from a loadout specification.
    * The loadout specification contains a specific slot position for placing the item.
    * This request will (probably) be  coincidental with a number of other such requests based on that loadout
    * so items must be rigidly placed else cascade into a chaostic order.
    * Item swapping during the placement is not allowed.
    * @see `GUIDTask.RegisterEquipment`
    * @see `PutEquipmentInInventorySlot`
    * @see `Task`
    * @see `TaskResolver.GiveTask`
    * @param obj the container
    * @param taskResolver na
    * @param item the item being manipulated
    * @param slot where the item will be placed in the container
    * @return a `TaskResolver` object
    */
  def PutLoadoutEquipmentInInventory(obj : PlanetSideServerObject with Container, taskResolver : ActorRef)(item : Equipment, slot : Int) : TaskResolver.GiveTask = {
    val localZone = obj.Zone
    TaskResolver.GiveTask(
      new Task() {
        private val localContainer = obj
        private val localItem = item
        private val localSlot = slot
        private val localFunc : (Equipment,Int)=>Future[Any] = PutEquipmentInInventorySlot(obj, taskResolver)

        override def Timeout : Long = 1000

        override def isComplete : Task.Resolution.Value = {
          if(localItem.HasGUID && localContainer.Find(localItem).nonEmpty)
            Task.Resolution.Success
          else
            Task.Resolution.Incomplete
        }

        override def Description : String = s"PutEquipmentInInventorySlot - ${localItem.Definition.Name}"

        def Execute(resolver : ActorRef) : Unit = {
          localFunc(localItem, localSlot)
          resolver ! scala.util.Success(this)
        }
      },
      List(GUIDTask.RegisterEquipment(item)(localZone.GUID))
    )
  }

  /**
    * Used for purchasing new equipment from a terminal and placing it somewhere in a player's loadout.
    * Two levels of query are performed here based on the behavior expected of the item.
    * First, an attempt is made to place the item anywhere in the target container as long as it does not cause swap items to be generated.
    * Second, if it fails admission to the target container, an attempt is made to place it into the target player's free hand.
    * If the container and the suggested player are the same, it will skip the second attempt.
    * As a terminal operation, the player must receive a report regarding whether the transaction was successful.
    * @see `ask`
    * @see `Containable.CanNotPutItemInSlot`
    * @see `Containable.PutItemInSlotOnly`
    * @see `GUIDTask.RegisterEquipment`
    * @see `GUIDTask.UnregisterEquipment`
    * @see `Future.onComplete`
    * @see `PutEquipmentInInventorySlot`
    * @see `TerminalMessageOnTimeout`
    * @param obj the container
    * @param taskResolver na
    * @param player na
    * @param term na
    * @param item the item being manipulated
    * @return a `TaskResolver` object
    */
  def BuyNewEquipmentPutInInventory(obj : PlanetSideServerObject with Container, taskResolver : ActorRef, player : Player, term : PlanetSideGUID)(item : Equipment) : TaskResolver.GiveTask = {
    val localZone = obj.Zone
    TaskResolver.GiveTask(
      new Task() {
        private val localContainer = obj
        private val localItem = item
        private val localPlayer = player
        private val localResolver = taskResolver
        private val localTermMsg : Boolean=>Unit = TerminalResult(term, localPlayer, TransactionType.Buy)

        override def Timeout : Long = 1000

        override def isComplete : Task.Resolution.Value = {
          if(localItem.HasGUID && localContainer.Find(localItem).nonEmpty)
            Task.Resolution.Success
          else
            Task.Resolution.Incomplete
        }

        def Execute(resolver : ActorRef) : Unit = {
          TerminalMessageOnTimeout(
            ask(localContainer.Actor, Containable.PutItemAway(localItem)),
            localTermMsg
          )
            .onComplete {
              case scala.util.Failure(_) | scala.util.Success(_ : Containable.CanNotPutItemInSlot) =>
                if(localContainer != localPlayer) {
                  TerminalMessageOnTimeout(
                    PutEquipmentInInventorySlot(localPlayer, localResolver)(localItem, Player.FreeHandSlot),
                    localTermMsg
                  )
                    .onComplete {
                      case scala.util.Failure(_) | scala.util.Success(_ : Containable.CanNotPutItemInSlot) =>
                        localTermMsg(false)
                      case _ =>
                        localTermMsg(true)
                    }
                }
                else {
                  localResolver ! GUIDTask.UnregisterEquipment(localItem)(localContainer.Zone.GUID)
                  localTermMsg(false)
                }
              case _ =>
                localTermMsg(true)
            }
          resolver ! scala.util.Success(this)
        }
      },
      List(GUIDTask.RegisterEquipment(item)(localZone.GUID))
    )
  }

  /**
    * The primary use is to register new mechanized assault exo-suit armaments,
    * place the newly registered weapon in hand,
    * and then raise that hand (draw that slot) so that the weapon is active.
    * (Players in MAX suits can not manipulate their drawn slot manually.)
    * In general, this can be used for any equipment that is to be equipped to a player's hand then immediately drawn.
    * Do not allow the item to be (mis)placed in any available slot.
    * Item swapping during the placement is not allowed and the possibility should be proactively avoided.
    * @throws `RuntimeException` if slot is not a player visible slot (holsters)
    * @see `ask`
    * @see `AvatarAction.ObjectDelete`
    * @see `AvatarAction.SendResponse`
    * @see `Containable.CanNotPutItemInSlot`
    * @see `Containable.PutItemInSlotOnly`
    * @see `GUIDTask.RegisterEquipment`
    * @see `GUIDTask.UnregisterEquipment`
    * @see `Future.onComplete`
    * @see `ObjectHeldMessage`
    * @see `Player.DrawnSlot`
    * @see `Player.LastDrawnSlot`
    * @see `Service.defaultPlayerGUID`
    * @see `TaskResolver.GiveTask`
    * @see `Zone.AvatarEvents`
    * @param player the player whose visible slot will be equipped and drawn
    * @param taskResolver na
    * @param item the item to equip
    * @param slot the slot in which the item will be equipped
    * @return a `TaskResolver` object
    */
  def HoldNewEquipmentUp(player : Player, taskResolver : ActorRef)(item : Equipment, slot : Int) : TaskResolver.GiveTask = {
    if(player.VisibleSlots.contains(slot)) {
      val localZone = player.Zone
      TaskResolver.GiveTask(
        new Task() {
          private val localPlayer = player
          private val localGUID = player.GUID
          private val localItem = item
          private val localSlot = slot
          private val localResolver = taskResolver

          override def Timeout : Long = 1000

          override def isComplete : Task.Resolution.Value = {
            if(localPlayer.DrawnSlot == localSlot)
              Task.Resolution.Success
            else
              Task.Resolution.Incomplete
          }

          def Execute(resolver : ActorRef) : Unit = {
            ask(localPlayer.Actor, Containable.PutItemInSlotOnly(localItem, localSlot))
              .onComplete {
                case scala.util.Failure(_) | scala.util.Success(_ : Containable.CanNotPutItemInSlot) =>
                  localResolver ! GUIDTask.UnregisterEquipment(localItem)(localZone.GUID)
                case _ =>
                  if(localPlayer.DrawnSlot != Player.HandsDownSlot) {
                    localPlayer.DrawnSlot = Player.HandsDownSlot
                    localZone.AvatarEvents ! AvatarServiceMessage(localPlayer.Name,
                      AvatarAction.SendResponse(Service.defaultPlayerGUID, ObjectHeldMessage(localGUID, Player.HandsDownSlot, false))
                    )
                    localZone.AvatarEvents ! AvatarServiceMessage(localZone.Id, AvatarAction.ObjectHeld(localGUID, localPlayer.LastDrawnSlot))
                  }
                  localPlayer.DrawnSlot = localSlot
                  localZone.AvatarEvents ! AvatarServiceMessage(localZone.Id,
                    AvatarAction.SendResponse(Service.defaultPlayerGUID, ObjectHeldMessage(localGUID, localSlot, false))
                  )
              }
            resolver ! scala.util.Success(this)
          }
        },
        List(GUIDTask.RegisterEquipment(item)(localZone.GUID))
      )
    }
    else {
      //TODO log.error
      throw new RuntimeException(s"provided slot $slot is not a player visible slot (holsters)")
    }
  }

  /**
    * Get an item from the ground and put it into the given container.
    * The zone in which the item is found is expected to be the same in which the container object is located.
    * If the object can not be placed into the container, it is put back on the ground.
    * The item that was collected off the ground, if it is placed back on the ground,
    * will be positioned with respect to the container object rather than its original location.
    * @see `ask`
    * @see `AvatarAction.ObjectDelete`
    * @see `Future.onComplete`
    * @see `Zone.AvatarEvents`
    * @see `Zone.Ground.CanNotPickUpItem`
    * @see `Zone.Ground.ItemInHand`
    * @see `Zone.Ground.PickUpItem`
    * @see `PutEquipmentInInventoryOrDrop`
    * @param obj the container into which the item will be placed
    * @param item the item being collected from off the ground of the container's zone
    * @return a `Future` that anticipates the resolution to this manipulation
    */
  def PickUpEquipmentFromGround(obj : PlanetSideServerObject with Container)(item : Equipment) : Future[Any] = {
    val localZone = obj.Zone
    val localContainer = obj
    val localItem = item
    val future = ask(localZone.Ground, Zone.Ground.PickupItem(item.GUID))
    future.onComplete {
      case scala.util.Success(Zone.Ground.ItemInHand(_)) =>
        PutEquipmentInInventoryOrDrop(localContainer)(localItem)
      case scala.util.Success(Zone.Ground.CanNotPickupItem(_, item_guid, _)) =>
        localZone.GUID(item_guid) match {
          case Some(_) => ;
          case None => //acting on old data?
            localZone.AvatarEvents ! AvatarServiceMessage(localZone.Id, AvatarAction.ObjectDelete(Service.defaultPlayerGUID, item_guid))
        }
      case _ => ;
    }
    future
  }

  /**
    * Remove an item from a container and drop it on the ground.
    * @see `ask`
    * @see `AvatarAction.ObjectDelete`
    * @see `Containable.ItemFromSlot`
    * @see `Containable.RemoveItemFromSlot`
    * @see `Future.onComplete`
    * @see `Future.recover`
    * @see `tell`
    * @see `Zone.AvatarEvents`
    * @see `Zone.Ground.DropItem`
    * @param obj the container to search
    * @param item the item to find and remove from the container
    * @param pos an optional position where to drop the item on the ground;
    *            expected override from original container's position
    * @return a `Future` that anticipates the resolution to this manipulation
    */
  def DropEquipmentFromInventory(obj : PlanetSideServerObject with Container)(item : Equipment, pos : Option[Vector3] = None) : Future[Any] = {
    val localContainer = obj
    val localItem = item
    val localPos = pos
    val result = ask(localContainer.Actor, Containable.RemoveItemFromSlot(localItem))
    result.onComplete {
      case scala.util.Success(Containable.ItemFromSlot(_, Some(_), Some(_))) =>
        localContainer.Zone.Ground.tell(Zone.Ground.DropItem(localItem, localPos.getOrElse(localContainer.Position), Vector3.z(localContainer.Orientation.z)), localContainer.Actor)
      case _ => ;
    }
    result
  }

  /**
    * Remove an item from a container and delete it.
    * @see `ask`
    * @see `AvatarAction.ObjectDelete`
    * @see `Containable.ItemFromSlot`
    * @see `Containable.RemoveItemFromSlot`
    * @see `Future.onComplete`
    * @see `Future.recover`
    * @see `GUIDTask.UnregisterEquipment`
    * @see `Zone.AvatarEvents`
    * @param obj the container to search
    * @param taskResolver na
    * @param item the item to find and remove from the container
    * @return a `Future` that anticipates the resolution to this manipulation
    */
  def RemoveOldEquipmentFromInventory(obj : PlanetSideServerObject with Container, taskResolver : ActorRef)(item : Equipment) : Future[Any] = {
    val localContainer = obj
    val localItem = item
    val localResolver = taskResolver
    val result = ask(localContainer.Actor, Containable.RemoveItemFromSlot(localItem))
    result.onComplete {
      case scala.util.Success(Containable.ItemFromSlot(_, Some(_), Some(_))) =>
        localResolver ! GUIDTask.UnregisterEquipment(localItem)(localContainer.Zone.GUID)
      case _ =>
    }
    result
  }

  /**
    * Primarily, remove an item from a container and delete it.
    * As a terminal operation, the player must receive a report regarding whether the transaction was successful.
    * At the end of a successful transaction, and only a successful transaction,
    * the item that was removed is no longer considered a valid game object.
    * Contrasting `RemoveOldEquipmentFromInventory` which identifies the actual item to be eliminated,
    * this function uses the slot where the item is (should be) located.
    * @see `ask`
    * @see `Containable.ItemFromSlot`
    * @see `Containable.RemoveItemFromSlot`
    * @see `Future.onComplete`
    * @see `Future.recover`
    * @see `GUIDTask.UnregisterEquipment`
    * @see `RemoveOldEquipmentFromInventory`
    * @see `TerminalMessageOnTimeout`
    * @see `TerminalResult`
    * @param obj the container to search
    * @param taskResolver na
    * @param player the player who used the terminal
    * @param term the unique identifier number of the terminal
    * @param slot from which slot the equipment is to be removed
    * @return a `Future` that anticipates the resolution to this manipulation
    */
  def SellEquipmentFromInventory(obj : PlanetSideServerObject with Container, taskResolver : ActorRef, player : Player, term : PlanetSideGUID)(slot : Int) : Future[Any] = {
    val localContainer = obj
    val localPlayer = player
    val localSlot = slot
    val localResolver = taskResolver
    val localTermMsg : Boolean=>Unit = TerminalResult(term, localPlayer, TransactionType.Sell)
    val result = TerminalMessageOnTimeout(
      ask(localContainer.Actor, Containable.RemoveItemFromSlot(localSlot)),
      localTermMsg
    )
    result.onComplete {
      case scala.util.Success(Containable.ItemFromSlot(_, Some(item), Some(_))) =>
        localResolver ! GUIDTask.UnregisterEquipment(item)(localContainer.Zone.GUID)
        localTermMsg(true)
      case _ =>
        localTermMsg(false)
    }
    result
  }

  /**
    * If a timeout occurs on the manipulation, declare a terminal transaction failure.
    * @see `AskTimeoutException`
    * @see `recover`
    * @param future the item manipulation's `Future` object
    * @param terminalMessage how to call the terminal message
    * @return a `Future` that anticipates the resolution to this manipulation
    */
  def TerminalMessageOnTimeout(future : Future[Any], terminalMessage : Boolean=>Unit) : Future[Any] = {
    future.recover {
      case _ : AskTimeoutException =>
        terminalMessage(false)
    }
  }

  /**
    * Announced the result of this player's terminal use, to the player that used the terminal.
    * This is a necessary step for regaining terminal use which is naturally blocked by the client after a transaction request.
    * @see `AvatarAction.TerminalOrderResult`
    * @see `ItemTransactionResultMessage`
    * @see `TransactionType`
    * @param guid the terminal's unique identifier
    * @param player the player who used the terminal
    * @param transaction what kind of transaction was involved in terminal use
    * @param result the result of that transaction
    */
  def TerminalResult(guid : PlanetSideGUID, player : Player, transaction : TransactionType.Value)(result : Boolean) : Unit = {
    player.Zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.TerminalOrderResult(guid, transaction, result))
  }

  /**
    * Drop some items on the ground is a given location.
    * The location corresponds to the previous container for those items.
    * @see `Zone.Ground.DropItem`
    * @param container the original object that contained the items
    * @param drops the items to be dropped on the ground
    */
  def DropLeftovers(container : PlanetSideServerObject with Container)(drops : List[InventoryItem]) : Unit = {
    //drop or retire
    val zone = container.Zone
    val pos = container.Position
    val orient = Vector3.z(container.Orientation.z)
    //TODO make a sound when dropping stuff?
    drops.foreach { entry => zone.Ground.tell(Zone.Ground.DropItem(entry.obj, pos, orient), container.Actor) }
  }

  /**
    * Within a specified `Container`, find the smallest number of `Equipment` objects of a certain qualifying type
    * whose sum count is greater than, or equal to, a `desiredAmount` based on an accumulator method.<br>
    * <br>
    * In an occupied `List` of returned `Inventory` entries, all but the last entry is typically considered "emptied."
    * For objects with contained quantities, the last entry may require having that quantity be set to a non-zero number.
    * @param obj the `Container` to search
    * @param filterTest test used to determine inclusivity of `Equipment` collection
    * @param desiredAmount how much is requested
    * @param counting test used to determine value of found `Equipment`;
    *                 defaults to one per entry
    * @return a `List` of all discovered entries totaling approximately the amount requested
    */
  def FindEquipmentStock(obj : Container,
                         filterTest : Equipment=>Boolean,
                         desiredAmount : Int,
                         counting : Equipment=>Int = DefaultCount) : List[InventoryItem] = {
    var currentAmount : Int = 0
    obj.Inventory.Items
      .filter(item => filterTest(item.obj))
      .sortBy(_.start)
      .takeWhile(entry => {
        val previousAmount = currentAmount
        currentAmount += counting(entry.obj)
        previousAmount < desiredAmount
      })
  }


  /**
    * The default counting function for an item.
    * Counts the number of item(s).
    * @param e the `Equipment` object
    * @return the quantity;
    *         always one
    */
  def DefaultCount(e : Equipment) : Int = 1

  /**
    * The counting function for an item of `AmmoBox`.
    * Counts the `Capacity` of the ammunition.
    * @param e the `Equipment` object
    * @return the quantity
    */
  def CountAmmunition(e : Equipment) : Int = {
    e match {
      case a : AmmoBox => a.Capacity
      case _ => 0
    }
  }

  /**
    * The counting function for an item of `Tool` where the item is also a grenade.
    * Counts the number of grenades.
    * @see `GlobalDefinitions.isGrenade`
    * @param e the `Equipment` object
    * @return the quantity
    */
  def CountGrenades(e : Equipment) : Int = {
    e match {
      case t : Tool => (GlobalDefinitions.isGrenade(t.Definition):Int) * t.Magazine
      case _ => 0
    }
  }

  /**
    * Flag an `AmmoBox` object that matches for the given ammunition type.
    * @param ammo the type of `Ammo` to check
    * @param e the `Equipment` object
    * @return `true`, if the object is an `AmmoBox` of the correct ammunition type; `false`, otherwise
    */
  def FindAmmoBoxThatUses(ammo : Ammo.Value)(e : Equipment) : Boolean = {
    e match {
      case t : AmmoBox => t.AmmoType == ammo
      case _ => false
    }
  }

  /**
    * Flag a `Tool` object that matches for loading the given ammunition type.
    * @param ammo the type of `Ammo` to check
    * @param e the `Equipment` object
    * @return `true`, if the object is a `Tool` that loads the correct ammunition type; `false`, otherwise
    */
  def FindToolThatUses(ammo : Ammo.Value)(e : Equipment) : Boolean = {
    e match {
      case t : Tool =>
        t.Definition.AmmoTypes.map { _.AmmoType }.contains(ammo)
      case _ =>
        false
    }
  }
}
