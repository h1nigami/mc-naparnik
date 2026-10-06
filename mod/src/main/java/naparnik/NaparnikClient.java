package naparnik;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;

@Environment(EnvType.CLIENT)
public final class NaparnikClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(NaparnikMod.COMPANION, SteveRenderer::new);
    }

    private static final class SteveRenderer extends HumanoidMobRenderer<Villager, AvatarRenderState, PlayerModel> {
        private static final Identifier SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

        SteveRenderer(EntityRendererProvider.Context context) {
            super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        }

        @Override
        public AvatarRenderState createRenderState() {
            return new AvatarRenderState();
        }

        @Override
        public Identifier getTextureLocation(AvatarRenderState state) {
            return SKIN;
        }
    }
}
