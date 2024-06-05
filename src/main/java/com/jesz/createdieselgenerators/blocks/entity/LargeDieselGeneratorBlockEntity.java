package com.jesz.createdieselgenerators.blocks.entity;

import com.jesz.createdieselgenerators.blocks.LargeDieselGeneratorBlock;
import com.jesz.createdieselgenerators.compat.computercraft.CCProxy;
import com.jesz.createdieselgenerators.other.FuelTypeManager;
import com.jesz.createdieselgenerators.sounds.SoundRegistry;
import com.simibubi.create.compat.computercraft.AbstractComputerBehaviour;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.utility.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.jesz.createdieselgenerators.blocks.DieselGeneratorBlock.POWERED;
import static com.jesz.createdieselgenerators.blocks.DieselGeneratorBlock.SILENCED;
import static com.jesz.createdieselgenerators.blocks.LargeDieselGeneratorBlock.*;

public class LargeDieselGeneratorBlockEntity extends GeneratingKineticBlockEntity {
    public WeakReference<LargeDieselGeneratorBlockEntity> controller = new WeakReference<>(null);
    BlockPos controllerPos = null;
    int tick;
    public int length;
    int lastLength;
    Fluid lastFluid;

    public LargeDieselGeneratorBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    public SmartFluidTankBehaviour tank;

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        if (computerBehaviour.isPeripheralCap(cap))
            return computerBehaviour.getPeripheralCapability();
        if (getBlockState().getValue(PIPE)) {
            LargeDieselGeneratorBlockEntity controller = this.controller.get();
            if (cap == ForgeCapabilities.FLUID_HANDLER && (side == Direction.UP || side == null))
                if (controller != null)
                    return controller.tank.getCapability().cast();
                else
                    return tank.getCapability().cast();
        }
        return super.getCapability(cap, side);
    }
    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        compound.putInt("Tick", tick);
        if(controllerPos != null)
            compound.put("Controller", NbtUtils.writeBlockPos(controllerPos));
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        tick = compound.getInt("Tick");
        controllerPos = NbtUtils.readBlockPos(compound.getCompound("Controller"));
    }
    public ScrollOptionBehaviour<WindmillBearingBlockEntity.RotationDirection> movementDirection;
    public AbstractComputerBehaviour computerBehaviour;

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(computerBehaviour = CCProxy.behaviour(this));

        movementDirection = new ScrollOptionBehaviour<>(WindmillBearingBlockEntity.RotationDirection.class,
                Lang.translateDirect("contraptions.windmill.rotation_direction"), this, new LargeDieselGeneratorValueBox());
        movementDirection.withCallback($ -> onDirectionChanged());

        behaviours.add(movementDirection);
        tank = SmartFluidTankBehaviour.single(this, 1000);
        behaviours.add(tank);
        super.addBehaviours(behaviours);
    }
    public void onDirectionChanged()
    {
        LargeDieselGeneratorBlockEntity controller = this.controller.get();
        if(controller == null)
            return;
        LargeDieselGeneratorBlockEntity lastEngine = getBackEngine();
        if(lastEngine == null)
            return;
        while (lastEngine != null) {
            lastEngine.movementDirection.setValue(movementDirection.getValue());
            lastEngine = lastEngine.getBackEngine();
        }
    }

    @Override
    public void initialize() {
        updateConnectivity();
        super.initialize();
    }

    @Override
    public float calculateAddedStressCapacity() {
        float capacity = 0;
        if (worldPosition.equals(controllerPos) && getGeneratedSpeed() != 0 && validFuel)
            capacity = FuelTypeManager.getGeneratedStress(this, tank.getPrimaryHandler().getFluid().getFluid())/Math.abs(getGeneratedSpeed()) * length;
        this.lastCapacityProvided = capacity;
        return capacity;
    }

    @Override
    public float getGeneratedSpeed() {
        if(!validFuel || !worldPosition.equals(controllerPos))
            return 0;
        return convertToDirection((movementDirection.getValue() == 1 ? -1 : 1) * FuelTypeManager.getGeneratedSpeed(this, tank.getPrimaryHandler().getFluid().getFluid()), getBlockState().getValue(HORIZONTAL_FACING));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean added = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        LargeDieselGeneratorBlockEntity controller = this.controller.get();
        if (!IRotate.StressImpact.isEnabled() || controller == null)
            return added;
        float stressBase = controller.calculateAddedStressCapacity();
        if (Mth.equal(stressBase, 0))
            return added;
        if(controller != this){
            Lang.translate("gui.goggles.generator_stats")
                    .forGoggles(tooltip);
            Lang.translate("tooltip.capacityProvided")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            float stressTotal = Math.abs(controller.getGeneratedSpeed()* stressBase);

            Lang.number(stressTotal)
                    .translate("generic.unit.stress")
                    .style(ChatFormatting.AQUA)
                    .space()
                    .add(Lang.translate("gui.goggles.at_current_speed")
                            .style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);

        }
        return containedFluidTooltip(tooltip, isPlayerSneaking, controller.tank.getCapability().cast());
    }
    int soundCounter = 0;
    boolean validFuel;
    @Override
    public void tick() {
        if(getBlockState().getValue(POWERED))
            validFuel = false;
        else
            validFuel = FuelTypeManager.getGeneratedSpeed(this, tank.getPrimaryHandler().getFluid().getFluid()) != 0;

        if(!tank.getPrimaryHandler().getFluid().getFluid().isSame(lastFluid) || length != lastLength) {
            lastFluid = tank.getPrimaryHandler().getFluid().getFluid();
            lastLength = length;
            reActivateSource = true;
        }
        super.tick();

        LargeDieselGeneratorBlockEntity controller = this.controller.get();
        if(controller == null)
            return;
        if(controller != this)
            tank.getPrimaryHandler().drain(controller.tank.getPrimaryHandler().fill(tank.getPrimaryHandler().getFluid(), IFluidHandler.FluidAction.EXECUTE), IFluidHandler.FluidAction.EXECUTE);

        if(soundCounter > FuelTypeManager.getSoundSpeed(controller.tank.getPrimaryHandler().getFluid().getFluid()) && controller.validFuel && !getBlockState().getValue(SILENCED)){
            level.playLocalSound(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), SoundRegistry.DIESEL_ENGINE_SOUND.get(), SoundSource.BLOCKS, 0.5f,1f, false);
            soundCounter = 0;
        }else
            soundCounter++;

        tick++;
        if(tick >= 20){
            tick = 0;
            if(validFuel)
                if(tank.getPrimaryHandler().getFluid().getAmount() >= FuelTypeManager.getBurnRate(this, tank.getPrimaryHandler().getFluid().getFluid()) * length)
                    tank.getPrimaryHandler().setFluid(FluidHelper.copyStackWithAmount(tank.getPrimaryHandler().getFluid(),
                            tank.getPrimaryHandler().getFluid().getAmount() - FuelTypeManager.getBurnRate(this, tank.getPrimaryHandler().getFluid().getFluid()) * length));
                else
                    tank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
        }
    }
    public LargeDieselGeneratorBlockEntity getBackEngine() {
        Direction facing = getBlockState().getValue(HORIZONTAL_FACING);
        if(facing.getAxisDirection() == Direction.AxisDirection.POSITIVE)
            facing = facing.getOpposite();
        LargeDieselGeneratorBlockEntity be = level.getBlockEntity(worldPosition.relative(facing), BlockEntityRegistry.LARGE_DIESEL_ENGINE.get()).orElse(null);
        return be == null ? null : be.getBlockState().getValue(FACING).getAxis() != getBlockState().getValue(FACING).getAxis() ? null : be;
    }
    public LargeDieselGeneratorBlockEntity getFrontEngine() {
        Direction facing = getBlockState().getValue(HORIZONTAL_FACING);
        if(facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE)
            facing = facing.getOpposite();
        LargeDieselGeneratorBlockEntity be = level.getBlockEntity(worldPosition.relative(facing), BlockEntityRegistry.LARGE_DIESEL_ENGINE.get()).orElse(null);
        return be == null ? null : be.getBlockState().getValue(FACING).getAxis() != getBlockState().getValue(FACING).getAxis() ? null : be;
    }
    public void updateConnectivity(){
        LargeDieselGeneratorBlockEntity frontEngine = getFrontEngine();
        if(frontEngine != null){
            frontEngine.updateConnectivity();
            return;
        }
        LargeDieselGeneratorBlockEntity backEngine = getBackEngine();
        controller = new WeakReference<>(this);
        controllerPos = worldPosition;
        if(backEngine == null){
            length = 1;
            return;
        }
        LargeDieselGeneratorBlockEntity lastEngine = backEngine;
        int length = 1;
        for (;lastEngine != null; length++) {
            lastEngine = lastEngine.getBackEngine();
        }
        this.length = length;
        lastEngine = backEngine;
        while (lastEngine != null) {
            lastEngine.length = length;
            lastEngine.controller = controller;
            lastEngine.controllerPos = controllerPos;
            lastEngine = lastEngine.getBackEngine();
        }
    }
    public void removed() {
        LargeDieselGeneratorBlockEntity lastEngine = getBackEngine();
        while (lastEngine != null) {
            tank.getPrimaryHandler().drain(lastEngine.tank.getPrimaryHandler().fill(tank.getPrimaryHandler().getFluid(), IFluidHandler.FluidAction.EXECUTE), IFluidHandler.FluidAction.EXECUTE);
            lastEngine.length = 1;
            lastEngine.controller = new WeakReference<>(null);
            lastEngine = lastEngine.getBackEngine();
        }
    }

}
