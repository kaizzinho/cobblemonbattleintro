package com.kaizzinho.battleintroduction

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import java.util.UUID

enum class BattleIntroKind {
    TRAINER,
    PVP,
    WILD_BOSS,
    RAID,
    LEGENDARY,
    MYTHICAL,
    ALPHA
}

object BattleIntroColors {
    // alpha intros always use the red eye-glare palette
    const val ALPHA_LAVA_RED: Int = 0xCF1020
}

data class BattleIntroStartPayload(
    val battleId: UUID,
    val kind: BattleIntroKind,
    val opponentActorUuid: UUID,
    val opponentEntityUuid: UUID?,
    val opponentPokemonUuid: UUID?,
    val opponentName: String,
    val detail: String,
    val level: Int,
    val colorRgb: Int,
    val opponentPokemonUuids: List<UUID>,
    val opponentBallItemIds: List<String>
) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    private fun write(buf: RegistryByteBuf) {
        buf.writeUuid(battleId)
        buf.writeString(kind.name)
        buf.writeUuid(opponentActorUuid)
        writeNullableUuid(buf, opponentEntityUuid)
        writeNullableUuid(buf, opponentPokemonUuid)
        buf.writeString(opponentName)
        buf.writeString(detail)
        buf.writeInt(level)
        buf.writeInt(colorRgb)
        writeUuidList(buf, opponentPokemonUuids)
        writeStringList(buf, opponentBallItemIds)
    }

    companion object {
        private const val MAX_PARTY_SIZE = 6

        val ID =
            CustomPayload.Id<BattleIntroStartPayload>(
                Identifier.of(
                    "battleintroduction",
                    "intro_start"
                )
            )

        val CODEC: PacketCodec<RegistryByteBuf, BattleIntroStartPayload> =
            PacketCodec.of(
                BattleIntroStartPayload::write,
                ::read
            )

        private fun read(
            buf: RegistryByteBuf
        ): BattleIntroStartPayload =
            BattleIntroStartPayload(
                battleId = buf.readUuid(),
                kind = readKind(buf),
                opponentActorUuid = buf.readUuid(),
                opponentEntityUuid = readNullableUuid(buf),
                opponentPokemonUuid = readNullableUuid(buf),
                opponentName = buf.readString(),
                detail = buf.readString(),
                level = buf.readInt(),
                colorRgb = buf.readInt(),
                opponentPokemonUuids = readUuidList(buf),
                opponentBallItemIds = readStringList(buf)
            )

        private fun readKind(
            buf: RegistryByteBuf
        ): BattleIntroKind =
            runCatching {
                BattleIntroKind.valueOf(
                    buf.readString()
                )
            }.getOrDefault(
                BattleIntroKind.TRAINER
            )

        private fun writeNullableUuid(
            buf: RegistryByteBuf,
            value: UUID?
        ) {
            buf.writeBoolean(value != null)
            value?.let(buf::writeUuid)
        }

        private fun readNullableUuid(
            buf: RegistryByteBuf
        ): UUID? =
            if (buf.readBoolean()) {
                buf.readUuid()
            } else {
                null
            }

        private fun writeUuidList(
            buf: RegistryByteBuf,
            values: List<UUID>
        ) {
            val limited = values.take(MAX_PARTY_SIZE)
            buf.writeInt(limited.size)
            limited.forEach(buf::writeUuid)
        }

        private fun readUuidList(
            buf: RegistryByteBuf
        ): List<UUID> {
            val count =
                buf.readInt()
                    .coerceIn(0, MAX_PARTY_SIZE)

            return List(count) {
                buf.readUuid()
            }
        }

        private fun writeStringList(
            buf: RegistryByteBuf,
            values: List<String>
        ) {
            val limited = values.take(MAX_PARTY_SIZE)
            buf.writeInt(limited.size)
            limited.forEach(buf::writeString)
        }

        private fun readStringList(
            buf: RegistryByteBuf
        ): List<String> {
            val count =
                buf.readInt()
                    .coerceIn(0, MAX_PARTY_SIZE)

            return List(count) {
                buf.readString()
            }
        }
    }
}

object BattleIntroNetworking {

    private var registered = false

    fun register() {
        if (registered) {
            return
        }

        registered = true

        PayloadTypeRegistry.playS2C().register(
            BattleIntroStartPayload.ID,
            BattleIntroStartPayload.CODEC
        )
    }
}
