package net.dvmn2.resourcecontrol.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C {@code dvmn2:rc_limit}: the largest size of ONE pack, in bytes, that the server asks the player
 * to allow. It is only a request: the player's decision is stored and checked on the client alone.
 * <pre>
 * VarInt  protocol
 * VarLong maxPackBytes
 * </pre>
 */
public record LimitPayload(int protocol, long maxPackBytes) implements CustomPayload {

    public static final CustomPayload.Id<LimitPayload> ID =
            new CustomPayload.Id<>(Identifier.of("dvmn2", "rc_limit"));

    public static final PacketCodec<PacketByteBuf, LimitPayload> CODEC = PacketCodec.of(
            LimitPayload::write,
            LimitPayload::read);

    private static void write(LimitPayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.protocol());
        buf.writeVarLong(payload.maxPackBytes());
    }

    private static LimitPayload read(PacketByteBuf buf) {
        try {
            return new LimitPayload(buf.readVarInt(), buf.readVarLong());
        } catch (RuntimeException ex) {
            buf.skipBytes(buf.readableBytes());
            return new LimitPayload(ResourceControlProtocol.INVALID_PROTOCOL, 0L);
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
