package com.letseries.letmelook.client.net;

import com.letseries.letmelook.client.LetMeLookClient;
import com.letseries.letmelook.client.render.GhostLayer;
import com.letseries.letmelook.common.Codec;
import com.letseries.letmelook.common.LetMeLookProtocol;
import com.letseries.letmelook.common.LookupStatus;
import com.letseries.letmelook.common.TimelineChunk;
import java.io.IOException;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * W2/W3 networking: bidirectional {@code letmelook:lookup} payloads.
 *
 * <ul>
 *   <li>S2C: gzipped-or-plain {@link TimelineChunk} pages and
 *       {@link LookupStatus} acknowledgements, multiplexed on one channel and
 *       routed by the JSON {@code kind} field (W1/W2 payloads have no
 *       {@code kind} and decode as timeline pages).</li>
 *   <li>C2S: JSON {@code LookupRequest} bytes sent via
 *       {@link ClientPlayNetworking#send(CustomPayload)} (Bukkit PluginMessage
 *       compatible: the client payload bytes land on the server's
 *       {@code PluginMessageListener} for the same channel).</li>
 * </ul>
 *
 * <p>W2 registered the S2C payload twice (here and in the now-removed
 * {@code TimelineReceiver}), which made Fabric throw
 * {@code IllegalArgumentException: already registered} during client
 * initialisation. Registration now happens exactly once, here.
 */
public final class NetChannels {

    /** Consumes server lifecycle acknowledgements on the client thread. */
    public interface StatusSink {
        void accept(LookupStatus status);
    }

    /** S2C payload on {@code letmelook:lookup}; must match the Bukkit channel. */
    public record TimelinePayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<TimelinePayload> ID =
                new CustomPayload.Id<>(Identifier.of("letmelook", "lookup"));
        public static final PacketCodec<RegistryByteBuf, TimelinePayload> CODEC =
                PacketCodec.of(
                        (payload, buf) -> buf.writeBytes(payload.data),
                        buf -> {
                            byte[] b = new byte[buf.readableBytes()];
                            buf.readBytes(b);
                            return new TimelinePayload(b);
                        });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** C2S lookup request payload (raw JSON bytes, same channel). */
    public record LookupPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<LookupPayload> ID =
                new CustomPayload.Id<>(Identifier.of("letmelook", "lookup"));
        public static final PacketCodec<RegistryByteBuf, LookupPayload> CODEC =
                PacketCodec.of(
                        (payload, buf) -> buf.writeBytes(payload.data),
                        buf -> {
                            byte[] b = new byte[buf.readableBytes()];
                            buf.readBytes(b);
                            return new LookupPayload(b);
                        });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private NetChannels() {}

    /**
     * Register payload types and the single S2C receiver.
     *
     * @param ghostLayer ingest target for timeline pages
     * @param statusSink handler for lifecycle acknowledgements
     */
    public static void register(GhostLayer ghostLayer, StatusSink statusSink) {
        PayloadTypeRegistry.playS2C().register(TimelinePayload.ID, TimelinePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(LookupPayload.ID, LookupPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(TimelinePayload.ID, (payload, context) -> {
            byte[] json;
            try {
                // Inflate once, then route on the cheap header peek. W1/W2
                // servers send plain JSON; W3 can send gzip.
                json = Codec.unwrap(payload.data());
            } catch (IOException e) {
                LetMeLookClient.LOGGER.warn("Dropped undecodable letmelook payload ({} bytes)",
                        payload.data().length, e);
                return;
            }
            if (LetMeLookProtocol.KIND_STATUS.equals(Codec.peekKind(json))) {
                final LookupStatus status;
                try {
                    status = Codec.decode(json, LookupStatus.class);
                } catch (Exception e) {
                    LetMeLookClient.LOGGER.warn("Malformed LookupStatus", e);
                    return;
                }
                context.client().execute(() -> statusSink.accept(status));
                return;
            }
            final TimelineChunk chunk;
            try {
                chunk = Codec.decode(json, TimelineChunk.class);
            } catch (Exception e) {
                LetMeLookClient.LOGGER.warn("Malformed TimelineChunk", e);
                return;
            }
            context.client().execute(() -> ghostLayer.ingest(chunk));
        });
    }
}
