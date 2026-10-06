package naparnik;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;

public final class SteveAppearanceTest implements FabricClientGameTest {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("forceload add -16 -16 16 16");
            server.waitFor(s -> s.overworld().isLoaded(new BlockPos(0, 100, 0)));
            server.runCommand("fill -8 99 -8 8 99 8 minecraft:stone");
            server.runCommand("fill -8 100 -8 8 104 8 minecraft:air");
            server.runCommand("gamemode creative @p");
            server.runCommand("tp @p 0.5 100 5.5 180 0");
            server.runCommand("execute positioned 0.5 100 0.5 run naparnik spawn");

            int id = server.computeOnServer(s -> {
                var uuid = NaparnikMod.BOTS.keySet().iterator().next();
                var body = s.overworld().getEntityInAnyDimension(uuid);
                if (!(body instanceof Villager mob) || !mob.isNoAi()
                        || !mob.isPersistenceRequired() || mob.getMaxHealth() != 20f) {
                    throw new AssertionError("Изменились свойства тела напарника");
                }
                return body.getId();
            });

            context.waitFor(mc -> mc.level != null && mc.level.getEntity(id) != null);
            context.runOnClient(mc -> {
                Mob body = (Mob) mc.level.getEntity(id);
                LivingEntityRenderer renderer = (LivingEntityRenderer) mc.getEntityRenderDispatcher().getRenderer(body);
                if (!(renderer.getModel() instanceof PlayerModel)) {
                    throw new AssertionError("Напарник должен использовать модель игрока");
                }
                LivingEntityRenderState state = (LivingEntityRenderState) renderer.createRenderState(body, 0f);
                if (!renderer.getTextureLocation(state).toString().equals("minecraft:textures/entity/player/wide/steve.png")) {
                    throw new AssertionError("Напарник должен использовать скин Стива");
                }
            });

            server.runCommand("tick freeze");
            world.getConnection().waitForChunksRender();
            context.takeScreenshot("naparnik-steve");
        }
    }
}
