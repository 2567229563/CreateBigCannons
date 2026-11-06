package rbasamoyai.createbigcannons.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.annotation.Nullable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.IEventBus;
import rbasamoyai.createbigcannons.CBCCompatTransformers;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import rbasamoyai.createbigcannons.utils.CBCUtils;

public class SableCompat {

    public static void onModCtor(IEventBus modBus, IEventBus forgeBus) {
        SableForceGroupsCompat.init(modBus);
        forgeBus.addListener(SableCompat::onPrePhysicsTick);
    }

    private static final Map<ResourceKey<Level>, Queue<CBCSableForce>> BY_DIMENSION = new ConcurrentHashMap<>();

    public static void enqueueForce(ServerLevel level, Vec3 pos, Vec3 force, int steps, ForceGroup forceGroup) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (subLevel == null) return;
        int safeSteps = Math.max(1, steps);
        CBCSableForce forceData = new CBCSableForce(subLevel.getUniqueId(), pos, force, safeSteps, forceGroup);
        enqueue(level.dimension(), forceData);
    }

    public static void enqueue(ResourceKey<Level> level, CBCSableForce forceData) {
        BY_DIMENSION.computeIfAbsent(level, ignored -> new ConcurrentLinkedDeque<>()).add(forceData);
    }

    public static List<CBCSableForce> drain(ResourceKey<Level> level) {
        Queue<CBCSableForce> forces = BY_DIMENSION.get(level);
        if (forces == null || forces.isEmpty()) return List.of();
        List<CBCSableForce> drained = new ArrayList<>();
        CBCSableForce force;
        while ((force = forces.poll()) != null)
            drained.add(force);
        return drained;
    }

    public static BlockPos transformFromShip(Level level, BlockPos pos, BlockPos root) {
        // Adapted from ActiveSableCompanion; this implementation is mainly leveraged for cannon particle handling as
        // the cannon particle spawn points can be far away from the cannon mount. --ritchie
        SubLevel sublevel = Sable.HELPER.getContaining(level, root);
        if (sublevel == null)
            return pos;
        Pose3dc pose = level instanceof LevelPoseProviderExtension extension ? extension.sable$getPose(sublevel) : sublevel.logicalPose();
        return BlockPos.containing(pose.transformPosition(Vec3.atCenterOf(pos)));
    }

    public static Vec3 transformFromShip(Level level, Vec3 pos, Vec3 root) {
        // See the BlockPos version for commentary. --ritchie
        SubLevel sublevel = Sable.HELPER.getContaining(level, root);
        if (sublevel == null)
            return pos;
        Pose3dc pose = level instanceof LevelPoseProviderExtension extension ? extension.sable$getPose(sublevel) : sublevel.logicalPose();
        return pose.transformPosition(pos);
    }

    public static boolean groundProjectile(Level level, AbstractCannonProjectile projectile, BlockPos impactPos) {
        // TODO fix per wipex issue
        SubLevel sublevel = Sable.HELPER.getContaining(level, impactPos);
        if (sublevel == null)
            return false;
        Vec3 projPos = projectile.position();
        Pose3d pose = sublevel.logicalPose();
        Vec3 shipPos = pose.transformPositionInverse(projPos);
        Vec3 nudge = Vec3.atCenterOf(impactPos).subtract(shipPos).scale(0.05);
        projectile.setPos(shipPos.add(nudge));
        Vec3 orientation = projectile.getOrientation();
        projectile.setOrientation(pose.transformNormalInverse(pose.transformNormalInverse(orientation)));
        return true;
    }

    public static void onCommonSetup() {
        CBCCompatTransformers.addBlockPosTransformer(SableCompat::transformFromShip);
        CBCCompatTransformers.addVec3Transformer(SableCompat::transformFromShip);
        CBCCompatTransformers.addNormalTransformer(new SableCannonProjectileCompat.NormalTransformer());
        CBCCompatTransformers.addProjectileFallHandler(new SableCannonProjectileCompat.ProjectileFallHandler());
        CBCCompatTransformers.addProjectileGroundingHandler(SableCompat::groundProjectile);
        CBCCompatTransformers.addProjectileClipHandler(SableCompat::clipProjectile);
        CBCCompatTransformers.addExplosionPreparer(SableCompat::refreshSubLevelBounds);
    }

    /**
     * Sable refreshes a sub-level's global bounding box at the start of its tick, before the physics step that
     * moves it. Explosion ray queries use that cached box, so at high speed the leading edge of a moving
     * structure lies outside the box and the explosion passes through it. Refresh the box immediately before a
     * CBC explosion so the box and the current pose describe the same position.
     */
    public static void refreshSubLevelBounds(Level level) {
        if (!(level instanceof ServerLevel serverLevel))
            return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container == null)
            return;
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (!subLevel.isRemoved())
                subLevel.updateBoundingBox();
        }
    }

    /**
     * Sable updates a sub-level's pose during the physics step, but its cached global bounding box is only
     * refreshed at the start of the sub-level tick. A fast shell can therefore be tested against a pose and a
     * bounding box that describe two different positions, and {@link Level#clip} misses the leading edge of a
     * fast-moving structure entirely. This performs the same clip in the sub-level's local space, using the
     * structure's movement over the remainder of the tick as the relative displacement between the shell and
     * the structure.
     */
    @Nullable
    public static CBCCompatTransformers.ProjectileClipResult clipProjectile(Level level, AbstractCannonProjectile projectile,
                                                                            Vec3 start, Vec3 end, double remainingTickFraction,
                                                                            BlockHitResult currentHit) {
        if (!(level instanceof ServerLevel serverLevel))
            return null;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container == null)
            return null;
        List<ServerSubLevel> subLevels = container.getAllSubLevels();
        if (subLevels.isEmpty())
            return null;

        double nearestDistance = Double.MAX_VALUE;
        if (currentHit.getType() != HitResult.Type.MISS) {
            Vec3 currentLoc = CBCCompatTransformers.transformVec3(level, currentHit.getLocation());
            nearestDistance = start.distanceTo(currentLoc);
        }

        CBCCompatTransformers.ProjectileClipResult result = null;
        double reach = projectile.getBbWidth() * 0.5d + 0.5d;
        AABB pathBounds = new AABB(start, end).inflate(reach);

        for (ServerSubLevel subLevel : subLevels) {
            if (subLevel.isRemoved())
                continue;
            Pose3dc pose = subLevel.logicalPose();
            Pose3dc lastPose = subLevel.lastPose();
            Vec3 fullDisplacement = new Vec3(pose.position().x() - lastPose.position().x(),
                pose.position().y() - lastPose.position().y(),
                pose.position().z() - lastPose.position().z());
            AABB subLevelBounds = toAabb(subLevel.boundingBox());
            AABB sweptBounds = subLevelBounds.expandTowards(fullDisplacement).expandTowards(fullDisplacement.reverse());
            if (!sweptBounds.inflate(reach).intersects(pathBounds))
                continue;

            Vec3 displacement = fullDisplacement.scale(remainingTickFraction);
            Vec3 localStart = pose.transformPositionInverse(start.add(displacement));
            Vec3 localEnd = pose.transformPositionInverse(end);
            double localLength = localStart.distanceTo(localEnd);
            if (localLength < 1.0E-4d)
                continue;

            ClipContext clipContext = new ClipContext(localStart, localEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.of(projectile));
            ((ClipContextExtension) clipContext).sable$setDoNotProject(true);
            BlockHitResult hitResult = level.clip(clipContext);
            if (hitResult.getType() == HitResult.Type.MISS)
                continue;
            if (Sable.HELPER.getContaining(level, hitResult.getBlockPos()) != subLevel)
                continue;

            double fraction = Math.max(1.0E-4d, Math.min(1.0d, localStart.distanceTo(hitResult.getLocation()) / localLength));
            Vec3 worldHit = CBCCompatTransformers.transformVec3(level, hitResult.getLocation());
            double distance = start.distanceTo(worldHit);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                result = new CBCCompatTransformers.ProjectileClipResult(hitResult, fraction);
            }
        }
        return result;
    }

    private static AABB toAabb(BoundingBox3dc bounds) {
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    public static void recoilCannon(Level level, Vec3 pos, Vec3 recoil) {
        if (level instanceof ServerLevel serverLevel) {
            ForceGroup forceGroup = SableForceGroupsCompat.RECOIL.get();
            Vec3 force = recoil.scale(CBCConfigs.server().compat.sableRecoilMultiplier.get());
            enqueueForce(serverLevel, pos, force, 1, forceGroup);
        }
    }

    public static void impactContraption(Level level, HitResult hitResult, AbstractCannonProjectile.ImpactResult impactResult, ProjectileContext projectileContext) {
        float massLost = projectileContext.projectile.massLost;
        if (level instanceof ServerLevel serverLevel) {
            Vec3 projDir = projectileContext.projectile.getDeltaMovement().normalize();
            Vec3 projLoc = hitResult.getLocation();
            SubLevel subLevel = Sable.HELPER.getContaining(level, projLoc);
            if (subLevel == null)
                return;

            ForceGroup forceGroup = SableForceGroupsCompat.IMPACT.get();
            double impactForceMultiplier = CBCConfigs.server().compat.sableImpactForceMultiplier.get();

            double impactPower = projectileContext.projectile.impactPower(projectileContext);

            if (impactResult.kinematics() == AbstractCannonProjectile.ImpactResult.KinematicOutcome.BOUNCE && hitResult instanceof BlockHitResult blockHitResult) {
                Vec3 surfaceNormal = CBCUtils.getSurfaceNormalVector(level, blockHitResult);
                Vec3 transformedNormal = subLevel.logicalPose().transformNormalInverse(surfaceNormal);
                double angle = Math.toDegrees(JOMLConversion.toJOML(surfaceNormal.reverse()).angle(JOMLConversion.toJOML(projDir)));
                enqueueForce(serverLevel, projLoc, transformedNormal.reverse().scale(impactForceMultiplier * impactPower * (1 - angle / 90)), 1, forceGroup);
            }
            else {
                Vec3 direction = subLevel.logicalPose().transformNormalInverse(projDir);
                Vec3 force = direction.scale(massLost * impactForceMultiplier * impactPower);
                enqueueForce(serverLevel, projLoc, force, 1, forceGroup);
            }
        }
    }

    public static void onPrePhysicsTick(ForgeSablePrePhysicsTickEvent event) {
        ServerLevel level = event.getPhysicsSystem().getLevel();
        List<CBCSableForce> forces = drain(level.dimension());
        if (forces.isEmpty())
            return;

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null)
            return;

        for (CBCSableForce force : forces) {
            SubLevel subLevel = container.getSubLevel(force.sublevelId());
            if (!(subLevel instanceof ServerSubLevel serverSubLevel) || serverSubLevel.isRemoved())
                continue;
            QueuedForceGroup queuedForceGroup = serverSubLevel.getOrCreateQueuedForceGroup(force.forceGroup());
            queuedForceGroup.applyAndRecordPointForce(JOMLConversion.toJOML(force.pos()), JOMLConversion.toJOML(force.force()));

            if (force.remainSteps() > 1)
                enqueue(level.dimension(), force.nextStep());
        }
    }

}
