// Copyright (c) 2017 PSForever
package net.psforever.objects.serverobject.structures

import java.util.concurrent.TimeUnit

import akka.actor.ActorContext
import net.psforever.actors.zone.BuildingActor
import net.psforever.objects.{GlobalDefinitions, Player}
import net.psforever.objects.definition.ObjectDefinition
import net.psforever.objects.serverobject.generator.Generator
import net.psforever.objects.serverobject.hackable.Hackable
import net.psforever.objects.serverobject.painbox.Painbox
import net.psforever.objects.serverobject.resourcesilo.ResourceSilo
import net.psforever.objects.serverobject.terminals.CaptureTerminal
import net.psforever.objects.serverobject.tube.SpawnTube
import net.psforever.objects.zones.Zone
import net.psforever.packet.game.BuildingInfoUpdateMessage
import net.psforever.types.{PlanetSideEmpire, PlanetSideGUID, PlanetSideGeneratorState, Vector3}
import scalax.collection.{Graph, GraphEdge}
import akka.actor.typed.scaladsl.adapter._

class Building(
    private val name: String,
    private val building_guid: Int,
    private val map_id: Int,
    private val zone: Zone,
    private val buildingType: StructureType,
    private val buildingDefinition: BuildingDefinition
) extends AmenityOwner {

  /**
    * The map_id is the identifier number used in BuildingInfoUpdateMessage. This is the index that the building appears in the MPO file starting from index 1
    * The GUID is the identifier number used in SetEmpireMessage / Facility hacking / PlanetSideAttributeMessage.
    */
  private var faction: PlanetSideEmpire.Value = PlanetSideEmpire.NEUTRAL
  private var playersInSOI: List[Player]      = List.empty
  private val capitols                        = List("Thoth", "Voltan", "Neit", "Anguta", "Eisa", "Verica")
  private var forceDomeActive: Boolean        = false
  super.Zone_=(zone)
  super.GUID_=(PlanetSideGUID(building_guid)) //set
  Invalidate()                                //unset; guid can be used during setup, but does not stop being registered properly later

  override def toString = name

  def Name: String = name

  def MapId: Int = map_id

  def IsCapitol: Boolean = capitols.contains(name)
  def IsSubCapitol: Boolean = {
    Neighbours match {
      case Some(buildings: Set[Building]) => buildings.exists(x => capitols.contains(x.name))
      case None                           => false
    }
  }
  def ForceDomeActive: Boolean = forceDomeActive
  def ForceDomeActive_=(activated: Boolean): Boolean = {
    forceDomeActive = activated
    forceDomeActive
  }

  def Faction: PlanetSideEmpire.Value = faction

  override def Faction_=(fac: PlanetSideEmpire.Value): PlanetSideEmpire.Value = {
    faction = fac
    Faction
  }

  def PlayersInSOI: List[Player] = playersInSOI

  def PlayersInSOI_=(list: List[Player]): List[Player] = {
    if (playersInSOI.isEmpty && list.nonEmpty) {
      Amenities.collect {
        case box: Painbox =>
          box.Actor ! Painbox.Start()
      }
    } else if (playersInSOI.nonEmpty && list.isEmpty) {
      Amenities.collect {
        case box: Painbox =>
          box.Actor ! Painbox.Stop()
      }
    }
    playersInSOI = list
    playersInSOI
  }

  // Get all lattice neighbours
  def Neighbours: Option[Set[Building]] = {
    zone.Lattice find this match {
      case Some(x) => Some(x.diSuccessors.map(x => x.toOuter))
      case None    => None;
    }
  }

  def NtuLevel: Int = {
    //if we have a silo, get the NTU level
    Amenities.find(_.Definition == GlobalDefinitions.resource_silo) match {
      case Some(obj: ResourceSilo) =>
        obj.CapacitorDisplay.toInt
      case _ => //we have no silo; we have unlimited power
        10
    }
  }

  def Generator: Option[Generator] = {
    Amenities.find(_.isInstanceOf[Generator]) match {
      case Some(obj: Generator) => Some(obj)
      case _                    => None
    }
  }

  def CaptureTerminal: Option[CaptureTerminal] = {
    Amenities.find(_.isInstanceOf[CaptureTerminal]) match {
      case Some(term) => Some(term.asInstanceOf[CaptureTerminal])
      case _          => None
    }
  }

  def CaptureTerminalIsHacked: Boolean = {
    CaptureTerminal match {
      case Some(obj: CaptureTerminal) =>
        obj.HackedBy.isDefined
      case None => false
    }
  }

  // Get all lattice neighbours matching the specified faction
  def Neighbours(faction: PlanetSideEmpire.Value): Option[Set[Building]] = {
    this.Neighbours match {
      case Some(x: Set[Building]) =>
        val matching = x.filter(b => b.Faction == faction)
        if (matching.isEmpty) None else Some(matching)
      case None => None
    }
  }

  def infoUpdateMessage(): BuildingInfoUpdateMessage = {
    val ntuLevel: Int = NtuLevel
    //if we have a capture terminal, get the hack status & time (in milliseconds) from control console if it exists
    val (hacking, hackingFaction, hackTime): (Boolean, PlanetSideEmpire.Value, Long) = CaptureTerminal match {
      case Some(obj: CaptureTerminal with Hackable) =>
        obj.HackedBy match {
          case Some(Hackable.HackInfo(_, _, hfaction, _, start, length)) =>
            val hack_time_remaining_ms =
              TimeUnit.MILLISECONDS.convert(math.max(0, start + length - System.nanoTime), TimeUnit.NANOSECONDS)
            (true, hfaction, hack_time_remaining_ms)
          case _ =>
            (false, PlanetSideEmpire.NEUTRAL, 0L)
        }
      case _ =>
        (false, PlanetSideEmpire.NEUTRAL, 0L)
    }
    //if we have no generator, assume the state is "Normal"
    val (generatorState, boostGeneratorPain) = Generator match {
      case Some(obj) =>
        (obj.Condition, false) // todo: poll pain field strength
      case _ =>
        (PlanetSideGeneratorState.Normal, false)
    }
    //if we have spawn tubes, determine if any of them are active
    val (spawnTubesNormal, boostSpawnPain): (Boolean, Boolean) = {
      val o = Amenities.collect({ case tube: SpawnTube if !tube.Destroyed => tube })
      (o.nonEmpty, false) //TODO poll pain field strength
    }

    val latticeBenefit: Int = {
      if (Faction == PlanetSideEmpire.NEUTRAL) 0
      else {
        def FindLatticeBenefit(
            wantedBenefit: ObjectDefinition,
            subGraph: Graph[Building, GraphEdge.UnDiEdge]
        ): Boolean = {
          var found = false

          subGraph find this match {
            case Some(self) =>
              if (this.Definition == wantedBenefit) found = true
              else {
                self pathUntil (_.Definition == wantedBenefit) match {
                  case Some(_) => found = true
                  case None    => ;
                }
              }
            case None => ;
          }

          found
        }

        // Check this Building is on the lattice first
        zone.Lattice find this match {
          case Some(_) =>
            // todo: generator destruction state
            val subGraph = Zone.Lattice filter ((b: Building) =>
              b.Faction == this.Faction && !b.CaptureTerminalIsHacked && b.NtuLevel > 0
            )

            var stackedBenefit = 0
            if (FindLatticeBenefit(GlobalDefinitions.amp_station, subGraph)) stackedBenefit |= 1
            if (FindLatticeBenefit(GlobalDefinitions.comm_station_dsp, subGraph)) stackedBenefit |= 2
            if (FindLatticeBenefit(GlobalDefinitions.cryo_facility, subGraph)) stackedBenefit |= 4
            if (FindLatticeBenefit(GlobalDefinitions.comm_station, subGraph)) stackedBenefit |= 8
            if (FindLatticeBenefit(GlobalDefinitions.tech_plant, subGraph)) stackedBenefit |= 16

            stackedBenefit
          case None => 0;
        }
      }
    }

    BuildingInfoUpdateMessage(
      Zone.Number,
      MapId,
      ntuLevel,
      hacking,
      hackingFaction,
      hackTime,
      if (ntuLevel > 0) Faction else PlanetSideEmpire.NEUTRAL,
      0, // Field != 0 will cause malformed packet
      None,
      generatorState,
      spawnTubesNormal,
      forceDomeActive,
      latticeBenefit,
      48,    // cavern benefit
      Nil,   // unk4,
      0,     // unk5
      false, // unk6
      8,     // unk7 Field != 8 will cause malformed packet
      None,  // unk7x
      boostSpawnPain,
      boostGeneratorPain
    )
  }

  def BuildingType: StructureType = buildingType

  override def Zone_=(zone: Zone): Zone = Zone //building never leaves zone after being set in constructor

  override def Continent: String = Zone.id

  override def Continent_=(zone: String): String = Continent //building never leaves zone after being set in constructor

  def Definition: BuildingDefinition = buildingDefinition
}

object Building {
  final val NoBuilding: Building =
    new Building(name = "", 0, map_id = 0, Zone.Nowhere, StructureType.Platform, GlobalDefinitions.building) {
      override def Faction_=(faction: PlanetSideEmpire.Value): PlanetSideEmpire.Value = PlanetSideEmpire.NEUTRAL
      override def Amenities_=(obj: Amenity): List[Amenity]                           = Nil
      GUID = net.psforever.types.PlanetSideGUID(0)
    }

  def apply(name: String, guid: Int, map_id: Int, zone: Zone, buildingType: StructureType): Building = {
    new Building(name, guid, map_id, zone, buildingType, GlobalDefinitions.building)
  }

  def Structure(
      buildingType: StructureType,
      location: Vector3,
      rotation: Vector3,
      definition: BuildingDefinition
  )(name: String, guid: Int, map_id: Int, zone: Zone, context: ActorContext): Building = {
    val obj = new Building(name, guid, map_id, zone, buildingType, definition)
    obj.Position = location
    obj.Orientation = rotation
    obj.Actor = context.spawn(BuildingActor(zone, obj), s"$map_id-$buildingType-building").toClassic
    obj
  }

  def Structure(
      buildingType: StructureType,
      location: Vector3
  )(name: String, guid: Int, map_id: Int, zone: Zone, context: ActorContext): Building = {
    val obj = new Building(name, guid, map_id, zone, buildingType, GlobalDefinitions.building)
    obj.Position = location
    obj.Actor = context.spawn(BuildingActor(zone, obj), s"$map_id-$buildingType-building").toClassic
    obj
  }

  def Structure(
      buildingType: StructureType
  )(name: String, guid: Int, map_id: Int, zone: Zone, context: ActorContext): Building = {
    val obj = new Building(name, guid, map_id, zone, buildingType, GlobalDefinitions.building)
    obj.Actor = context.spawn(BuildingActor(zone, obj), s"$map_id-$buildingType-building").toClassic
    obj
  }

  def Structure(
      buildingType: StructureType,
      buildingDefinition: BuildingDefinition,
      location: Vector3
  )(name: String, guid: Int, id: Int, zone: Zone, context: ActorContext): Building = {
    val obj = new Building(name, guid, id, zone, buildingType, buildingDefinition)
    obj.Position = location
    obj.Actor = context.spawn(BuildingActor(zone, obj), s"$id-$buildingType-building").toClassic
    obj
  }
}
