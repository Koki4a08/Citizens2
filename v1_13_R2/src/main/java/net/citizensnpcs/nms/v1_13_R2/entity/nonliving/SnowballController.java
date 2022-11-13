package net.citizensnpcs.nms.v1_13_R2.entity.nonliving;

import net.minecraft.server.v1_13_R2.Tag;
import net.minecraft.server.v1_13_R2.FluidType;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_13_R2.CraftServer;
import org.bukkit.craftbukkit.v1_13_R2.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_13_R2.entity.CraftSnowball;
import org.bukkit.entity.Snowball;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.nms.v1_13_R2.entity.MobEntityController;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.util.Util;
import net.minecraft.server.v1_13_R2.EntitySnowball;
import net.minecraft.server.v1_13_R2.NBTTagCompound;
import net.minecraft.server.v1_13_R2.World;

public class SnowballController extends MobEntityController {
    public SnowballController() {
        super(EntitySnowballNPC.class);
    }

    @Override
    public Snowball getBukkitEntity() {
        return (Snowball) super.getBukkitEntity();
    }

    public static class SnowballNPC extends CraftSnowball implements NPCHolder {
        private final CitizensNPC npc;

        public SnowballNPC(EntitySnowballNPC entity) {
            super((CraftServer) Bukkit.getServer(), entity);
            this.npc = entity.npc;
        }

        @Override
        public NPC getNPC() {
            return npc;
        }
    }

    public static class EntitySnowballNPC extends EntitySnowball implements NPCHolder {
        @Override
        public boolean b(Tag<FluidType> tag) {
            double mx = motX;             double my = motY;             double mz = motZ;             boolean res = super.b(tag);             if (!npc.isPushableByFluids()) {                 motX = mx;                 motY = my;                 motZ = mz;             }             return res;
        }

        private final CitizensNPC npc;

        public EntitySnowballNPC(World world) {
            this(world, null);
        }

        @Override
        public boolean d(NBTTagCompound save) {
            return npc == null ? super.d(save) : false;
        }

        public EntitySnowballNPC(World world, NPC npc) {
            super(world);
            this.npc = (CitizensNPC) npc;
        }

        @Override
        public void collide(net.minecraft.server.v1_13_R2.Entity entity) {
            // this method is called by both the entities involved - cancelling
            // it will not stop the NPC from moving.
            super.collide(entity);
            if (npc != null) {
                Util.callCollisionEvent(npc, entity.getBukkitEntity());
            }
        }

        @Override
        public void f(double x, double y, double z) {
            Vector vector = Util.callPushEvent(npc, x, y, z);
            if (vector != null) {
                super.f(vector.getX(), vector.getY(), vector.getZ());
            }
        }

        @Override
        public CraftEntity getBukkitEntity() {
            if (npc != null && !(bukkitEntity instanceof NPCHolder)) {
                bukkitEntity = new SnowballNPC(this);
            }
            return super.getBukkitEntity();
        }

        @Override
        public NPC getNPC() {
            return npc;
        }

        @Override
        public void tick() {
            if (npc != null) {
                npc.update();
            } else {
                super.tick();
            }
        }
    }
}