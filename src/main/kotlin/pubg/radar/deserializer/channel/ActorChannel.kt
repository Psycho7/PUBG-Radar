package pubg.radar.deserializer.channel

import com.badlogic.gdx.math.*
import pubg.radar.*
import pubg.radar.deserializer.CHTYPE_ACTOR
import pubg.radar.deserializer.actor.repl_layout_bunch
import pubg.radar.struct.*
import pubg.radar.struct.Archetype.*
import pubg.radar.struct.NetGUIDCache.Companion.guidCache
import pubg.radar.struct.cmd.PlayerStateCMD.selfID
import pubg.radar.util.tuple2
import java.util.concurrent.ConcurrentHashMap

class ActorChannel(ChIndex: Int, client: Boolean = true): Channel(ChIndex, CHTYPE_ACTOR, client) {
  companion object: GameListener {
    init {
      register(this)
    }
    
    val actors = ConcurrentHashMap<NetworkGUID, Actor>()
    val visualActors = ConcurrentHashMap<NetworkGUID, Actor>()
    val airDropLocation = ConcurrentHashMap<NetworkGUID, Vector3>()
    val droppedItemLocation = ConcurrentHashMap<NetworkGUID, tuple2<Vector2, String>>()
    val corpseLocation = ConcurrentHashMap<NetworkGUID, Vector3>()
    
    override fun onGameOver() {
      actors.clear()
      visualActors.clear()
      airDropLocation.clear()
      droppedItemLocation.clear()
      corpseLocation.clear()
    }
  }
  
  var actor: Actor? = null
  
  override fun ReceivedBunch(bunch: Bunch) {
    if (client && bunch.bHasMustBeMappedGUIDs) {
      val NumMustBeMappedGUIDs = bunch.readUInt16()
      for (i in 0 until NumMustBeMappedGUIDs) {
        val guid = bunch.readNetworkGUID()
      }
    }
    ProcessBunch(bunch)
  }
  
  fun ProcessBunch(bunch: Bunch) {
    if (client && actor == null) {
      if (!bunch.bOpen) {
        return
      }
      SerializeActor(bunch)
      if (actor == null)
        return
    }
    if (!client && actor == null) {
      val clientChannel = inChannels[chIndex] ?: return
      actor = (clientChannel as ActorChannel).actor
      if (actor == null) return
    }
    val actor = actor!!
    val netPlayerIndex = bunch.readInt8()
    while (bunch.notEnd()) {
      //header
      val bHasRepLayout = bunch.readBit()
      val bIsActor = bunch.readBit()
      var repObj: String?
      if (bIsActor) {
        repObj = actor.Type.name
      } else {
        val (netguid, _subobj) = bunch.readObject()//SubObject, SubObjectNetGUID
        if (!client) {
          if (_subobj == null)// The server should never need to create sub objects
            continue
          repObj = _subobj.pathName
          bugln { "$actor hasSubObj $repObj" }
        } else {
          val bStablyNamed = bunch.readBit()
          if (bStablyNamed) {// If this is a stably named sub-object, we shouldn't need to create it
            if (_subobj == null)
              continue
            repObj = _subobj.pathName
          } else {
            val (classGUID, classObj) = bunch.readObject()//SubOjbectClass,SubObjectClassNetGUID
            if (classObj != null && actor.Type == DroopedItemGroup) {
              val sn = Item.isGood(classObj.pathName)
              if (sn != null)
                droppedItemLocation[netguid] = tuple2(Vector2(actor.location.x, actor.location.y), sn)
            }
            bugln { "subObjClass:${actor.netGUID} ${actor.archetype.pathName} classObj:$classObj" }
            if (!classGUID.isValid() || classObj == null)
              continue
            val subobj = NetGuidCacheObject(classObj.pathName, classGUID)
            guidCache.registerNetGUID_Client(netguid, subobj)
            repObj = subobj.pathName
          }
          
        }
      }
      val NumPayloadBits = bunch.readIntPacked()
      if (NumPayloadBits < 0 || NumPayloadBits > bunch.bitsLeft()) {
        bugln { "NumPayloadBits=$NumPayloadBits > bunch.bitsLeft()=${bunch.bitsLeft()}" }
        return
      }
      try {
        val outPayload = bunch.deepCopy(NumPayloadBits)
        
        info { ",${if (bHasRepLayout) "hasRepLayout" else "noRepLayout"},actor[${actor.netGUID.value}]archetype=${actor.archetype}" }
        if (bHasRepLayout) {
          if (!client)// Server shouldn't receive properties.
            return
          repl_layout_bunch(outPayload, repObj, actor)
        }
        if (!client && repObj == "CharMoveComp") {
          selfID = actor.netGUID
          while (outPayload.notEnd())
            charmovecomp(outPayload)
        }
      } catch (e: Exception) {
      }
      bunch.skipBits(NumPayloadBits)
    }
    infoln { "" }
  }
  
  fun SerializeActor(bunch: Bunch) {
    val (netGUID, newActor) = bunch.readObject()//NetGUID
    if (netGUID.isDynamic()) {
      val (archetypeNetGUID, archetype) = bunch.readObject()
      if (archetypeNetGUID.isValid() && archetype == null) {
        val existingCacheObjectPtr = guidCache.objectLoop[archetypeNetGUID]
        if (existingCacheObjectPtr != null)
          bugln { "Unresolved Archetype GUID. Path: ${existingCacheObjectPtr.pathName}, NetGUID: $archetypeNetGUID" }
        else
          bugln { "Unresolved Archetype GUID. Guid not registered!, NetGUID: $archetypeNetGUID" }
      }
      val bSerializeLocation = bunch.readBit()
      
      val Location = if (bSerializeLocation)
        bunch.readVector()
      else
        Vector3.Zero
      val bSerializeRotation = bunch.readBit()
      val Rotation = if (bSerializeRotation) bunch.readRotationShort() else Vector3.Zero
      
      val bSerializeScale = bunch.readBit()
      val Scale = if (bSerializeScale) bunch.readVector() else Vector3.Zero
      
      val bSerializeVelocity = bunch.readBit()
      val Velocity = if (bSerializeVelocity) bunch.readVector() else Vector3.Zero
      
      if (actor == null && archetype != null) {
        val _actor = Actor(netGUID, archetypeNetGUID, archetype, chIndex)
        with(_actor) {
          location = Location
          rotation = Rotation
          velocity = Velocity
          guidCache.registerNetGUID_Client(netGUID, this)
          actor = this
          if (client) {
            actors[netGUID] = this
            when (Type) {
              AirDrop -> airDropLocation[netGUID] = location
              DeathDropItemPackage -> corpseLocation[netGUID] = location
              else -> {
              }
            }
          }
        }
        bugln { "spawn $actor" }
      }
      bugln { ",[$netGUID] spawn:$Location,$Rotation,$Velocity, actor:$actor" }
    } else {
      if (newActor == null) return
      actor = Actor(netGUID, newActor.outerGUID, newActor, chIndex)
      actor!!.isStatic = true
    }
    
  }
  
  override fun close() {
    if (actor != null) {
      if (client) {
        actors.remove(actor!!.netGUID)
        visualActors.remove(actor!!.netGUID)
      }
      actor = null
    }
  }
  
}

