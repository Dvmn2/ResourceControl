package net.dvmn2.resourcecontrol.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S {@code dvmn2:rc_status}: how applying the pack set is going. Informational for server admins only.
 * <pre>
 * VarInt  protocol
 * VarInt  state     (0 = downloading, 1 = ready, 2 = failed)
 * String  message   (max 512 characters; technical text in English)
 * </pre>
 */
public record StatusPayload(int protocol, State state, String message) implements CustomPayload {

    public static final CustomPayload.Id<StatusPayload> ID =
            new CustomPayload.Id<>(Identifier.of("dvmn2", "rc_status"));

    public static final int MAX_MESSAGE_LENGTH = 512;

    public static final PacketCodec<PacketByteBuf, StatusPayload> CODEC = PacketCodec.of(
            StatusPayload::write,
            StatusPayload::read);

    /**
     * Reported state; the id is the wire value.
     */
    public enum State {
        DOWNLOADING(0),
        READY(1),
        FAILED(2);

        private final int id;

        State(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        static State fromId(int id) {
            for (State state : values()) {
                if (state.id == id) {
                    return state;
                }
            }
            throw new IllegalArgumentException("unknown state " + id);
        }
    }

    private static void write(StatusPayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.protocol());
        buf.writeVarInt(payload.state().getId());
        buf.writeString(payload.message(), MAX_MESSAGE_LENGTH);
    }

    private static StatusPayload read(PacketByteBuf buf) {
        try {
            return new StatusPayload(buf.readVarInt(), State.fromId(buf.readVarInt()),
                    buf.readString(MAX_MESSAGE_LENGTH));
        } catch (RuntimeException ex) {
            buf.skipBytes(buf.readableBytes());
            return new StatusPayload(ResourceControlProtocol.INVALID_PROTOCOL, State.FAILED, "");
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
