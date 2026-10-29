package dev.ross.glasses

import java.util.IdentityHashMap

/** Independent playback owners cannot clear each other's background-transfer exclusion. */
class MediaPlaybackOwners {
  private val owners = IdentityHashMap<Any, Boolean>()
  @Volatile var isActive = false
    private set

  @Synchronized fun setActive(owner: Any, active: Boolean) {
    if (active) owners[owner] = true else owners.remove(owner)
    isActive = owners.isNotEmpty()
  }
}
