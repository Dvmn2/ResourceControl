package net.dvmn2.resourcecontrol.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Payload от мода к плагину (канал {@code dvmn2:rc_status}): состояние применения набора паков.
 * <p>
 * Формат: VarInt protocol, VarInt state, String message.
 */
public record StatusPayload(int protocol, int state, String message) implements CustomPayload {

    public static final int STATE_DOWNLOADING = 0;
    public static final int STATE_READY = 1;
    public static final int STATE_FAILED = 2;

    public static final CustomPayload.Id<StatusPayload> ID =
            new CustomPayload.Id<>(Identifier.of("dvmn2", "rc_status"));

    public static final PacketCodec<PacketByteBuf, StatusPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.protocol());
                buf.writeVarInt(payload.state());
                buf.writeString(payload.message());
            },
            buf -> new StatusPayload(buf.readVarInt(), buf.readVarInt(), buf.readString(512))
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
