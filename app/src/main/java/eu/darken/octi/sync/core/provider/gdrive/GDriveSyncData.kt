package eu.darken.octi.sync.core.provider.gdrive

import eu.darken.octi.sync.core.Sync

data class GDriveSyncData(
    override val devices: Collection<Sync.Read.Device>
) : Sync.Read