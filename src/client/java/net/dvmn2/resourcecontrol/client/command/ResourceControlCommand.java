package net.dvmn2.resourcecontrol.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.dvmn2.resourcecontrol.client.ResourceControlConfig;
import net.dvmn2.resourcecontrol.client.state.ActivePacks;
import net.dvmn2.resourcecontrol.client.sync.SyncManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * Client command {@code /rc}: the player's emergency switch and status.
 * <pre>
 * /rc off             ignore the server and remove all packs
 * /rc on              enable again
 * /rc status          show the current state
 * /rc clear           remove saved packs
 * /rc shaders allow   allow packs with shaders
 * /rc shaders block   reject packs with shaders
 * </pre>
 * Subcommands unknown to the client (the server plugin's) are forwarded to the server by Fabric.
 */
public final class ResourceControlCommand {

    private final SyncManager sync;

    public ResourceControlCommand(SyncManager sync) {
        this.sync = sync;
    }

    /**
     * Builds the command tree for registration.
     */
    public LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("rc")
                .then(ClientCommandManager.literal("off").executes(this::off))
                .then(ClientCommandManager.literal("on").executes(this::on))
                .then(ClientCommandManager.literal("status").executes(this::status))
                .then(ClientCommandManager.literal("clear").executes(this::clear))
                .then(ClientCommandManager.literal("shaders")
                        .then(ClientCommandManager.literal("allow").executes(ctx -> shaders(ctx, false)))
                        .then(ClientCommandManager.literal("block").executes(ctx -> shaders(ctx, true))));
    }

    private int off(CommandContext<FabricClientCommandSource> ctx) {
        sync.setEnabled(ctx.getSource().getClient(), false);
        ctx.getSource().sendFeedback(Text.translatable("resourcecontrol.disabled"));
        return Command.SINGLE_SUCCESS;
    }

    private int on(CommandContext<FabricClientCommandSource> ctx) {
        sync.setEnabled(ctx.getSource().getClient(), true);
        ctx.getSource().sendFeedback(Text.translatable("resourcecontrol.enabled"));
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<FabricClientCommandSource> ctx) {
        ResourceControlConfig config = ResourceControlConfig.get();
        String mod = config.enabled ? "resourcecontrol.state.enabled" : "resourcecontrol.state.disabled";
        String shaders = config.blockShaders ? "resourcecontrol.state.blocked" : "resourcecontrol.state.allowed";
        ctx.getSource().sendFeedback(Text.translatable("resourcecontrol.status",
                Text.translatable(mod),
                Text.translatable(shaders),
                String.valueOf(ActivePacks.getEffective().size()),
                String.valueOf(ActivePacks.getPersistentCount())));
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandContext<FabricClientCommandSource> ctx) {
        sync.clearPersistent();
        ctx.getSource().sendFeedback(Text.translatable("resourcecontrol.cleared"));
        return Command.SINGLE_SUCCESS;
    }

    private int shaders(CommandContext<FabricClientCommandSource> ctx, boolean blocked) {
        sync.setShadersBlocked(ctx.getSource().getClient(), blocked);
        ctx.getSource().sendFeedback(Text.translatable(
                blocked ? "resourcecontrol.shaders.blocked" : "resourcecontrol.shaders.allowed"));
        return Command.SINGLE_SUCCESS;
    }
}
