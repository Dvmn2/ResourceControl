package net.dvmn2.resourcecontrol.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C {@code dvmn2:rc_sync}: the COMPLETE set of packs the player should have. Every message replaces
 * the previous one. A higher priority puts the pack on top of the others.
 * <pre>
 * VarInt  protocol
 * Boolean allowShaders
 * VarInt  count        (max 64)
 * count x {
 *   String  name        (max 64 characters)
 *   String  url         (max 1024 characters)
 *   String  sha1        (max 64 characters)
 *   VarLong size        (bytes)
 *   VarInt  priority
 *   Boolean persistent
 * }
 * </pre>
 */
public record SyncPayload(int protocol, boolean allowShaders, List<PackEntry> packs) implements CustomPayload {

    public static final CustomPayload.Id<SyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of("dvmn2", "rc_sync"));

    public static final int MAX_PACKS = 64;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_URL_LENGTH = 1024;
    public static final int MAX_SHA1_LENGTH = 64;

    public static final PacketCodec<PacketByteBuf, SyncPayload> CODEC = PacketCodec.of(
            SyncPayload::write,
            SyncPayload::read);

    /**
     * One pack of the wanted set.
     */
    public record PackEntry(String name, String url, String sha1, long size, int priority, boolean persistent) {
    }

    private static void write(SyncPayload payload, PacketByteBuf buf) {
        buf.writeVarInt(payload.protocol());
        buf.writeBoolean(payload.allowShaders());
        buf.writeVarInt(payload.packs().size());
        for (PackEntry entry : payload.packs()) {
            buf.writeString(entry.name());
            buf.writeString(entry.url());
            buf.writeString(entry.sha1());
            buf.writeVarLong(entry.size());
            buf.writeVarInt(entry.priority());
            buf.writeBoolean(entry.persistent());
        }
    }

    private static SyncPayload read(PacketByteBuf buf) {
        try {
            int protocol = buf.readVarInt();
            boolean allowShaders = buf.readBoolean();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_PACKS) {
                throw new IllegalArgumentException("pack count out of range: " + count);
            }
            List<PackEntry> packs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                packs.add(new PackEntry(
                        buf.readString(MAX_NAME_LENGTH),
                        buf.readString(MAX_URL_LENGTH),
                        buf.readString(MAX_SHA1_LENGTH),
                        buf.readVarLong(),
                        buf.readVarInt(),
                        buf.readBoolean()));
            }
            return new SyncPayload(protocol, allowShaders, List.copyOf(packs));
        } catch (RuntimeException ex) {
            buf.skipBytes(buf.readableBytes());
            return new SyncPayload(ResourceControlProtocol.INVALID_PROTOCOL, false, List.of());
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
