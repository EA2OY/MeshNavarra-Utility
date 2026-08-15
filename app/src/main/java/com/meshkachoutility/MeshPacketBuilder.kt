/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import com.google.protobuf.ByteString
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos.Data
import org.meshtastic.proto.MeshProtos.MeshPacket
import org.meshtastic.proto.MeshProtos.RouteDiscovery
import org.meshtastic.proto.MeshProtos.ToRadio
import org.meshtastic.proto.Portnums.PortNum

object MeshPacketBuilder {

    /**
     * When > 0, every outbound MeshPacket gets this hop_limit (low-impact/debug
     * mode: cap test traffic at 1 hop instead of the node's configured default).
     * Set by the app when the low-impact switch is ON.
     */
    @Volatile
    var lowImpactHopLimit: Int = 0

    private fun applyHopLimit(b: MeshPacket.Builder): MeshPacket.Builder {
        if (lowImpactHopLimit > 0) b.setHopLimit(lowImpactHopLimit)
        return b
    }

    /**
     * Constructs a ToRadio packet containing a reboot command.
     *
     * @param seconds Number of seconds to wait before rebooting.
     * @param targetNodeId The destination node ID (e.g., 0xFFFFFFFF for broadcast, or specific node ID).
     * @return The constructed ToRadio packet.
     */
    fun buildRebootPacket(seconds: Int, targetNodeId: Int = -1): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setRebootSeconds(seconds)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio packet containing a set_favorite_node command.
     *
     * @param nodeNum The node number to mark as favorite.
     * @param targetNodeId The destination node ID.
     * @return The constructed ToRadio packet.
     */
    fun buildSetFavoritePacket(nodeNum: Int, targetNodeId: Int = -1): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setSetFavoriteNode(nodeNum)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio packet containing a nodedb_reset command.
     *
     * @param keepFavorites If true, tells the node to preserve starred favorites through reset.
     * @param targetNodeId The destination node ID.
     * @return The constructed ToRadio packet.
     */
    fun buildNodeDbResetPacket(keepFavorites: Boolean, targetNodeId: Int = -1): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setNodedbReset(keepFavorites)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio packet containing a set_ignored_node command.
     *
     * @param nodeNum The node number to mark as ignored/muted.
     * @param targetNodeId The destination node ID.
     * @return The constructed ToRadio packet.
     */
    fun buildSetIgnoredPacket(nodeNum: Int, targetNodeId: Int = -1): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setSetIgnoredNode(nodeNum)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio packet containing a remove_favorite_node command.
     *
     * @param nodeNum The node number to remove from favorites.
     * @param targetNodeId The destination node ID.
     * @return The constructed ToRadio packet.
     */
    fun buildRemoveFavoritePacket(nodeNum: Int, targetNodeId: Int = -1): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setRemoveFavoriteNode(nodeNum)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio packet containing a remove_ignored_node command.
     *
     * @param nodeNum The node number to remove from the ignored list.
     * @param targetNodeId The destination node ID.
     * @return The constructed ToRadio packet.
     */
    fun buildRemoveIgnoredPacket(nodeNum: Int, targetNodeId: Int = -1): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setRemoveIgnoredNode(nodeNum)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio packet containing a remove_by_nodenum command
     * (removes a node entry from the target node's NodeDB, e.g. to clear a
     * stale key before a factory reset).
     *
     * @param nodeNum The node number to remove from the target's NodeDB.
     * @param targetNodeId The destination node ID.
     * @return The constructed ToRadio packet.
     */
    fun buildRemoveNodePacket(nodeNum: Int, targetNodeId: Int = -1): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setRemoveByNodenum(nodeNum)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio `want_config` request. The radio answers with its
     * MyNodeInfo, owner, node DB, configuration, channels and metadata, ending
     * with a config_complete_id matching [requestId] (equivalent to the
     * Meshtastic CLI `--info`/`--nodes` queries).
     *
     * @param requestId Non-zero id echoed back in the config_complete_id response.
     * @return The constructed ToRadio packet.
     */
    fun buildWantConfigPacket(requestId: Int): ToRadio {
        return ToRadio.newBuilder()
            .setWantConfigId(requestId)
            .build()
    }

    /**
     * Constructs a ToRadio text message packet. This is the transport used by the
     * NavaTastic `/nava ...` command set (send the command as text) and by plain
     * mesh text messages (`meshtastic --sendtext "..."`).
     *
     * @param text The text / command to send (e.g. "/nava ping").
     * @param targetNodeId Destination node number, or -1 for broadcast.
     * @param channelIndex LoRa channel index to use (0 = primary, 1 = Navadmin).
     * @param pkiEncrypted Whether to encrypt the payload with the destination's public key.
     * @param packetId Explicit MeshPacket.id for ACK/NAK correlation (0 = unset).
     * @param wantAck Request a routing ACK from the mesh (delivery indicator).
     * @return The constructed ToRadio packet.
     */
    fun buildTextPacket(
        text: String,
        targetNodeId: Int = -1,
        channelIndex: Int = 0,
        pkiEncrypted: Boolean = false,
        packetId: Int = 0,
        wantAck: Boolean = false
    ): ToRadio {
        val decodedData = Data.newBuilder()
            .setPortnum(PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8(text))
            .build()

        val meshPacket = applyHopLimit(MeshPacket.newBuilder()
            .setDecoded(decodedData)
            .setChannel(channelIndex)
            .setTo(targetNodeId)
            .setPkiEncrypted(pkiEncrypted))
            .apply {
                if (packetId != 0) setId(packetId)
                if (wantAck) setWantAck(true)
            }
            .build()

        return ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
    }

    /**
     * Constructs a ToRadio packet asking a node to report its telemetry
     * (battery, voltage, channel utilization...), like the Meshtastic CLI
     * `--request-telemetry`.
     *
     * The payload MUST carry a Telemetry message with device_metrics present:
     * without it the firmware replies NO_RESPONSE (error 8) to the request.
     *
     * @param targetNodeId Destination node number, or -1 for broadcast.
     * @return The constructed ToRadio packet.
     */
    fun buildRequestTelemetryPacket(targetNodeId: Int = -1): ToRadio {
        val telemetry = org.meshtastic.proto.TelemetryProtos.Telemetry.newBuilder()
            .setDeviceMetrics(
                org.meshtastic.proto.TelemetryProtos.DeviceMetrics.newBuilder()
                    .setBatteryLevel(0)
                    .build()
            )
            .build()

        val decodedData = Data.newBuilder()
            .setPortnum(PortNum.TELEMETRY_APP)
            .setPayload(telemetry.toByteString())
            .setWantResponse(true)
            .build()

        val meshPacket = applyHopLimit(MeshPacket.newBuilder()
            .setDecoded(decodedData)
            .setTo(targetNodeId))
            .build()

        return ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
    }

    /**
     * Constructs a ToRadio packet asking a node for a specific telemetry type
     * (environment, power/energy, air quality, signal stats or host metrics).
     * The request carries an empty Telemetry message with the requested metrics
     * field present.
     *
     * @param targetNodeId Destination node number, or -1 for broadcast.
     * @param which "env", "power", "air", "signal" or "host".
     * @return The constructed ToRadio packet.
     */
    fun buildRequestTelemetryTypePacket(targetNodeId: Int = -1, which: String): ToRadio {
        val t = org.meshtastic.proto.TelemetryProtos.Telemetry.newBuilder()
        when (which) {
            "env" -> t.setEnvironmentMetrics(org.meshtastic.proto.TelemetryProtos.EnvironmentMetrics.newBuilder().build())
            "air" -> t.setAirQualityMetrics(org.meshtastic.proto.TelemetryProtos.AirQualityMetrics.newBuilder().build())
            "signal" -> t.setLocalStats(org.meshtastic.proto.TelemetryProtos.LocalStats.newBuilder().build())
            "host" -> t.setHostMetrics(org.meshtastic.proto.TelemetryProtos.HostMetrics.newBuilder().build())
            else -> t.setPowerMetrics(org.meshtastic.proto.TelemetryProtos.PowerMetrics.newBuilder().build())
        }
        val telemetry = t.build()

        val decodedData = Data.newBuilder()
            .setPortnum(PortNum.TELEMETRY_APP)
            .setPayload(telemetry.toByteString())
            .setWantResponse(true)
            .build()

        val meshPacket = applyHopLimit(MeshPacket.newBuilder()
            .setDecoded(decodedData)
            .setTo(targetNodeId))
            .build()

        return ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
    }

    /**
     * Constructs a ToRadio packet asking a node for its neighbor info
     * (last heard neighbors with SNR), like the official app's "Neighbors".
     */
    fun buildRequestNeighborInfoPacket(targetNodeId: Int = -1): ToRadio {
        val decodedData = Data.newBuilder()
            .setPortnum(PortNum.NEIGHBORINFO_APP)
            .setWantResponse(true)
            .build()

        val meshPacket = applyHopLimit(MeshPacket.newBuilder()
            .setDecoded(decodedData)
            .setTo(targetNodeId))
            .build()

        return ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
    }

    /**
     * Constructs a ToRadio packet asking a node to return its current config
     * section (LORA_CONFIG, POSITION_CONFIG, DEVICE_CONFIG, ...). The response
     * arrives as an AdminMessage.get_config_response containing the full section.
     *
     * @param configType The config section to request.
     * @param targetNodeId Destination node number, or -1 for the local node.
     * @param packetId Unique id used to match the response.
     * @return The constructed ToRadio packet.
     */
    fun buildGetConfigRequestPacket(
        configType: AdminMessage.ConfigType,
        targetNodeId: Int = -1,
        packetId: Int = 0
    ): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setGetConfigRequest(configType)
            .build()
        return buildAdminToRadioPacket(adminMessage, targetNodeId, packetId)
    }

    /**
     * Constructs a ToRadio packet asking a node to send back its current GPS
     * position, like the Meshtastic CLI `--request-position`.
     *
     * @param targetNodeId Destination node number, or -1 for broadcast.
     * @return The constructed ToRadio packet.
     */
    fun buildRequestPositionPacket(targetNodeId: Int = -1): ToRadio {
        val decodedData = Data.newBuilder()
            .setPortnum(PortNum.POSITION_APP)
            .setWantResponse(true)
            .build()

        val meshPacket = applyHopLimit(MeshPacket.newBuilder()
            .setDecoded(decodedData)
            .setTo(targetNodeId))
            .build()

        return ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
    }

    /**
     * Constructs a ToRadio trace-route request toward a node, like the
     * Meshtastic CLI `--traceroute` (returns the intermediate hops with SNR).
     *
     * @param targetNodeId Destination node number, or -1 for broadcast.
     * @return The constructed ToRadio packet.
     */
    fun buildTraceRoutePacket(targetNodeId: Int = -1): ToRadio {
        val routeDiscovery = RouteDiscovery.newBuilder().build()
        val decodedData = Data.newBuilder()
            .setPortnum(PortNum.TRACEROUTE_APP)
            .setPayload(routeDiscovery.toByteString())
            .setWantResponse(true)
            .build()

        val meshPacket = MeshPacket.newBuilder()
            .setDecoded(decodedData)
            .setTo(targetNodeId)
            .build()

        return ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
    }

    /**
     * Constructs a ToRadio packet that changes the owner (long/short name) of a
     * node, like the Meshtastic CLI `--set-owner "..."`.
     *
     * @param longName New long name for the node.
     * @param shortName New short name (<= 4 characters).
     * @param targetNodeId Destination node number, or -1 for the local node.
     * @return The constructed ToRadio packet.
     */
    fun buildSetOwnerPacket(longName: String, shortName: String, targetNodeId: Int = -1): ToRadio {
        val user = org.meshtastic.proto.MeshProtos.User.newBuilder()
            .setLongName(longName)
            .setShortName(shortName)
            .build()

        val adminMessage = AdminMessage.newBuilder()
            .setSetOwner(user)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId)
    }

    /**
     * Constructs a ToRadio packet creating/updating a channel (e.g. the Navadmin
     * channel: index 1, name "Navadmin", PSK AQ==).
     */
    fun buildSetChannelPacket(
        channel: org.meshtastic.proto.ChannelProtos.Channel,
        targetNodeId: Int = -1,
        packetId: Int = 0
    ): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setSetChannel(channel)
            .build()
        return buildAdminToRadioPacket(adminMessage, targetNodeId, packetId)
    }

    /**
     * Constructs a ToRadio packet ordering a factory reset of the node.
     */
    fun buildFactoryResetPacket(targetNodeId: Int = -1, packetId: Int = 0): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setFactoryResetConfig(1)
            .build()
        return buildAdminToRadioPacket(adminMessage, targetNodeId, packetId)
    }

    /**
     * Constructs a ToRadio packet applying a full [ConfigProtos.Config] to a node
     * (remote administration). Used by the "Good Practices" batch, where the
     * caller tracks the packet id to wait for the ACK before sending the next
     * command.
     *
     * @param config The config message to apply (position, lora, ...).
     * @param targetNodeId Destination node number, or -1 for the local node.
     * @param packetId Unique id used to match the ACK response.
     * @return The constructed ToRadio packet.
     */
    fun buildConfigPacket(config: ConfigProtos.Config, targetNodeId: Int = -1, packetId: Int = 0): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setSetConfig(config)
            .build()

        return buildAdminToRadioPacket(adminMessage, targetNodeId, packetId)
    }

    /**
     * Constructs a ToRadio packet requesting a module config section
     * (MQTT, TELEMETRY, SERIAL, ...). The response is an AdminMessage with
     * get_module_config_response.
     */
    fun buildGetModuleConfigRequestPacket(
        configType: AdminMessage.ModuleConfigType,
        targetNodeId: Int = -1,
        packetId: Int = 0
    ): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setGetModuleConfigRequest(configType)
            .build()
        return buildAdminToRadioPacket(adminMessage, targetNodeId, packetId)
    }

    /**
     * Constructs a ToRadio packet applying a full module config section.
     */
    fun buildSetModuleConfigPacket(
        moduleConfig: org.meshtastic.proto.ModuleConfigProtos.ModuleConfig,
        targetNodeId: Int = -1,
        packetId: Int = 0
    ): ToRadio {
        val adminMessage = AdminMessage.newBuilder()
            .setSetModuleConfig(moduleConfig)
            .build()
        return buildAdminToRadioPacket(adminMessage, targetNodeId, packetId)
    }

    /**
     * Builds a device config setting how often the node broadcasts its NodeInfo
     * (default is 900 s / 15 minutes; 72 h = 259200 s).
     */
    fun buildDeviceConfig(nodeInfoSecs: Int): ConfigProtos.Config {
        return ConfigProtos.Config.newBuilder()
            .setDevice(
                ConfigProtos.Config.DeviceConfig.newBuilder()
                    .setNodeInfoBroadcastSecs(nodeInfoSecs)
                    .build()
            )
            .build()
    }

    /**
     * Builds a LoRa config limiting the hop count to [hops].
     */
    fun buildHopLimitConfig(hops: Int): ConfigProtos.Config {
        return ConfigProtos.Config.newBuilder()
            .setLora(
                ConfigProtos.Config.LoRaConfig.newBuilder()
                    .setHopLimit(hops)
                    .build()
            )
            .build()
    }

    /**
     * Builds a position config: GPS beacon interval, smart-position toggle and
     * GPS update interval.
     */
    fun buildPositionConfig(
        broadcastSecs: Int,
        smartEnabled: Boolean,
        gpsUpdateSecs: Int
    ): ConfigProtos.Config {
        return ConfigProtos.Config.newBuilder()
            .setPosition(
                ConfigProtos.Config.PositionConfig.newBuilder()
                    .setPositionBroadcastSecs(broadcastSecs)
                    .setPositionBroadcastSmartEnabled(smartEnabled)
                    .setGpsUpdateInterval(gpsUpdateSecs)
                    .build()
            )
            .build()
    }

    /**
     * Helper function to wrap an AdminMessage in a MeshPacket and ToRadio envelope.
     */
    private fun buildAdminToRadioPacket(adminMessage: AdminMessage, targetNodeId: Int, packetId: Int = 0): ToRadio {
        val payloadBytes = adminMessage.toByteString()

        val decodedData = Data.newBuilder()
            .setPortnum(PortNum.ADMIN_APP)
            .setPayload(payloadBytes)
            .setWantResponse(true)
            .build()

        // Remote admin must be PKI-encrypted: the target only validates the sender
        // as admin through a cryptographically-verified PKC packet (this is also
        // what marks the sender as admin in the Navarrico firmware).
        val meshPacket = applyHopLimit(MeshPacket.newBuilder()
            .setDecoded(decodedData)
            .setTo(targetNodeId)
            .setWantAck(true)
            .setId(packetId)
            .apply { if (targetNodeId != -1) setPkiEncrypted(true) })
            .build()

        return ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
    }
}
