package com.jesz.createdieselgenerators.blocks.entity;

import com.jesz.createdieselgenerators.blocks.DieselGeneratorBlock;
import com.jesz.createdieselgenerators.compat.computercraft.CCProxy;
import com.jesz.createdieselgenerators.config.ConfigRegistry;
import com.jesz.createdieselgenerators.other.FuelTypeManager;
import com.jesz.createdieselgenerators.sounds.SoundRegistry;
import com.simibubi.create.compat.computercraft.AbstractComputerBehaviour;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.utility.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;

import java.util.*;

import static com.jesz.createdieselgenerators.blocks.DieselGeneratorBlock.*;

public class DieselGeneratorBlockEntity extends GeneratingKineticBlockEntity {

    public boolean validFuel;
    public DieselGeneratorBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }
    public SmartFluidTankBehaviour tank;
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        if (computerBehaviour.isPeripheralCap(cap))
            return computerBehaviour.getPeripheralCapability();
        if(getBlockState().getValue(FACING) == Direction.DOWN) {
            if (cap == ForgeCapabilities.FLUID_HANDLER && side.getAxis() == Direction.Axis.X)
                return tank.getCapability().cast();
        }else if(getBlockState().getValue(FACING) == Direction.UP){
            if (cap == ForgeCapabilities.FLUID_HANDLER && side.getAxis() == Direction.Axis.Z)
                return tank.getCapability().cast();
        }else{
            if (cap == ForgeCapabilities.FLUID_HANDLER && side == Direction.DOWN)
                return tank.getCapability().cast();
        }
        return super.getCapability(cap, side);
    }
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap) {
        if(cap == ForgeCapabilities.FLUID_HANDLER)
            return tank.getCapability().cast();
        return super.getCapability(cap);
    }
    int partialSecond;

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        compound.putInt("PartialSecond", partialSecond);
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        partialSecond = compound.getInt("PartialSecond");
    }

    public AbstractComputerBehaviour computerBehaviour;
    public ScrollOptionBehaviour<WindmillBearingBlockEntity.RotationDirection> movementDirection;

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(computerBehaviour = CCProxy.behaviour(this));

        movementDirection = new ScrollOptionBehaviour<>(WindmillBearingBlockEntity.RotationDirection.class,
                Lang.translateDirect("contraptions.windmill.rotation_direction"), this, new DieselGeneratorValueBox());
        movementDirection.withCallback($ -> onDirectionChanged());

        behaviours.add(movementDirection);
        tank = SmartFluidTankBehaviour.single(this, 1000);
        tank.whenFluidUpdates(this::tankUpdated);
        behaviours.add(tank);
        super.addBehaviours(behaviours);
    }

    private void tankUpdated() {
        reActivateSource = true;
        if(!getBlockState().getValue(POWERED))
            validFuel = FuelTypeManager.getGeneratedSpeed(this, tank.getPrimaryHandler().getFluid().getFluid()) != 0;
    }

    public void onDirectionChanged(){}
    @Override
    public float calculateAddedStressCapacity() {
        if(getGeneratedSpeed() == 0 || getBlockState().getValue(POWERED))
            return 0;
        float capacity =  FuelTypeManager.getGeneratedStress(this, tank.getPrimaryHandler().getFluid().getFluid()) / Math.abs(getGeneratedSpeed());
        lastCapacityProvided = capacity;
        return  capacity;
    }

    @Override
    public float getGeneratedSpeed() {
        if(getBlockState().getValue(POWERED))
            return 0;
        return convertToDirection((movementDirection.getValue() == 1 ? -1 : 1)* FuelTypeManager.getGeneratedSpeed(this, tank.getPrimaryHandler().getFluid().getFluid()), getBlockState().getValue(DieselGeneratorBlock.FACING))*(getBlockState().getValue(TURBOCHARGED) ? ConfigRegistry.TURBOCHARGED_ENGINE_MULTIPLIER.get().floatValue() : 1);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean added = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!StressImpact.isEnabled())
            return added;

        float stressBase = calculateAddedStressCapacity();
        if (Mth.equal(stressBase, 0))
            return added;
        return containedFluidTooltip(tooltip, isPlayerSneaking, tank.getCapability().cast());
    }
    int soundTick = 0;

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide && !getBlockState().getValue(SILENCED))
            if (getBlockState().getValue(TURBOCHARGED) ? soundTick > FuelTypeManager.getSoundSpeed(tank.getPrimaryHandler().getFluid().getFluid()) / 2 : soundTick > FuelTypeManager.getSoundSpeed(tank.getPrimaryHandler().getFluid().getFluid())) {
                if (validFuel) {
                    soundTick = 0;
                    level.playLocalSound(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), SoundRegistry.DIESEL_ENGINE_SOUND.get(), SoundSource.BLOCKS, getBlockState().getValue(TURBOCHARGED) ? 0.5f : 0.3f, getBlockState().getValue(TURBOCHARGED) ? 1.1f : 1f, false);
                }
            } else {
                soundTick++;
            }
        if(getBlockState().getValue(POWERED))
            validFuel = false;
        else
            validFuel = FuelTypeManager.getGeneratedSpeed(this, tank.getPrimaryHandler().getFluid().getFluid()) != 0;

        partialSecond++;
        if (partialSecond >= 20) {
            partialSecond = 0;
            if (validFuel) {
                if (tank.getPrimaryHandler().getFluid().getAmount() >= FuelTypeManager.getBurnRate(this, tank.getPrimaryHandler().getFluid().getFluid()) * (!getBlockState().getValue(TURBOCHARGED) ? 1 : ConfigRegistry.TURBOCHARGED_ENGINE_BURN_RATE_MULTIPLIER.get().floatValue()))
                    tank.getPrimaryHandler().setFluid(FluidHelper.copyStackWithAmount(tank.getPrimaryHandler().getFluid(),
                            (int) (tank.getPrimaryHandler().getFluid().getAmount() - FuelTypeManager.getBurnRate(this, tank.getPrimaryHandler().getFluid().getFluid()) * (!getBlockState().getValue(TURBOCHARGED) ? 1 : ConfigRegistry.TURBOCHARGED_ENGINE_BURN_RATE_MULTIPLIER.get().floatValue()))));
                else
                    tank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
            }
        }
    }
}
