package net.dvmn2.resourcecontrol.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Payload от плагина (канал {@code dvmn2:rc_limit}): максимальный размер ОДНОГО пака в байтах,
 * который сервер просит разрешить скачивать. Это только ЗАПРОС: по нему клиент спрашивает игрока,
 * а решение игрока хранится и проверяется исключительно на клиенте (см. {@code ConsentStore}).
 * <p>
 * Формат: VarInt protocol, VarLong maxPackBytes.
 */
public record LimitPayload(int protocol, long maxPackBytes) implements CustomPayload {

    public static final CustomPayload.Id<LimitPayload> ID =
            new CustomPayload.Id<>(Identifier.of("dvmn2", "rc_limit"));

    public static final PacketCodec<PacketByteBuf, LimitPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.protocol());
                buf.writeVarLong(payload.maxPackBytes());
            },
            buf -> new LimitPayload(buf.readVarInt(), buf.readVarLong())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
