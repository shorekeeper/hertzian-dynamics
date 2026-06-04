package io.hertzian.dynamics.command;

import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import io.hertzian.dynamics.HertzianConfig;
import io.hertzian.dynamics.core.RfCore;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Operator command for the compute backend. Exposes the realism neutral
 * compute knobs at runtime: it reports the dispatch counters, switches the
 * analyzer DFT backend (auto, cpu, gpu), sets the Auto threshold, and
 * toggles the diagnostic log. None of these change the simulation result;
 * they only choose where a workload runs.
 *
 * <p>
 * Permission level two (ops). The master GPU enable and the physical
 * world-flavor presets are not reachable here because they are applied at
 * core creation; changing them requires editing the config and reloading
 * the world. Changes made by this command are runtime only and revert to
 * the config file on restart.
 *
 * <p>
 * Command processing runs on the server main thread, the same thread the
 * radio tick mixes on, so the policy writes are serialised against the mix
 * loop without extra locking.
 */
public final class CommandHertzianCompute extends CommandBase {

    @Override
    public String getCommandName() {
        return "hzcompute";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/hzcompute <stats | zoomdft auto|cpu|gpu | threshold <n> | log on|off>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            sendStats(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats":
                sendStats(sender);
                break;
            case "zoomdft": {
                if (args.length < 2) {
                    msg(sender, EnumChatFormatting.RED, "usage: /hzcompute zoomdft <auto|cpu|gpu>");
                    return;
                }
                int mode = parseMode(args[1]);
                if (mode < 0) {
                    msg(sender, EnumChatFormatting.RED, "unknown mode: " + args[1]);
                    return;
                }
                HertzianConfig.zoomDftMode = mode;
                WorldRfState.setComputePolicyAll(
                        RfCore.WORKLOAD_ZOOM_DFT,
                        mode,
                        HertzianConfig.zoomDftAutoThreshold);
                msg(
                        sender,
                        EnumChatFormatting.GREEN,
                        "zoom DFT backend set to " + modeName(mode) + " (runtime only, not persisted)");
                break;
            }
            case "raycast": {
                if (args.length < 2) {
                    msg(sender, EnumChatFormatting.RED, "usage: /hzcompute raycast <auto|cpu|gpu>");
                    return;
                }
                int mode = parseMode(args[1]);
                if (mode < 0) {
                    msg(sender, EnumChatFormatting.RED, "unknown mode: " + args[1]);
                    return;
                }
                HertzianConfig.propagationMode = mode;
                WorldRfState.setComputePolicyAll(
                        RfCore.WORKLOAD_PROPAGATION,
                        mode,
                        HertzianConfig.propagationAutoThreshold);
                msg(
                        sender,
                        EnumChatFormatting.GREEN,
                        "raycast backend set to " + modeName(mode) + " (runtime only, not persisted)");
                break;
            }
            case "threshold": {
                if (args.length < 2) {
                    msg(sender, EnumChatFormatting.RED, "usage: /hzcompute threshold <n>");
                    return;
                }
                int t;
                try {
                    t = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    msg(sender, EnumChatFormatting.RED, "not a number: " + args[1]);
                    return;
                }
                if (t < 0) t = 0;
                HertzianConfig.zoomDftAutoThreshold = t;
                WorldRfState.setComputePolicyAll(RfCore.WORKLOAD_ZOOM_DFT, HertzianConfig.zoomDftMode, t);
                msg(sender, EnumChatFormatting.GREEN, "zoom DFT auto threshold set to " + t + " (runtime only)");
                break;
            }
            case "log": {
                if (args.length < 2) {
                    msg(sender, EnumChatFormatting.RED, "usage: /hzcompute log <on|off>");
                    return;
                }
                boolean on = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                HertzianConfig.logComputeBackend = on;
                msg(sender, EnumChatFormatting.GREEN, "compute backend logging " + (on ? "on" : "off"));
                break;
            }
            default:
                msg(sender, EnumChatFormatting.RED, getCommandUsage(sender));
        }
    }

    private void sendStats(ICommandSender sender) {
        msg(
                sender,
                EnumChatFormatting.AQUA,
                "zoom DFT backend=" + modeName(HertzianConfig.zoomDftMode)
                        + " threshold=" + HertzianConfig.zoomDftAutoThreshold
                        + " gpuEnabled=" + HertzianConfig.gpuEnabled);
        msg(
                sender,
                EnumChatFormatting.AQUA,
                "raycast backend=" + modeName(HertzianConfig.propagationMode)
                        + " threshold=" + HertzianConfig.propagationAutoThreshold);
        List<String> rlines = WorldRfState.describeComputeAll(RfCore.WORKLOAD_PROPAGATION);
        for (String l : rlines) {
            msg(sender, EnumChatFormatting.GRAY, l);
        }
        List<String> lines = WorldRfState.describeComputeAll(RfCore.WORKLOAD_ZOOM_DFT);
        if (lines.isEmpty()) {
            msg(sender, EnumChatFormatting.GRAY, "no active worlds");
            return;
        }
        for (String l : lines) {
            msg(sender, EnumChatFormatting.GRAY, l);
        }
    }

    private static void msg(ICommandSender sender, EnumChatFormatting color, String text) {
        sender.addChatMessage(new ChatComponentText(color + "[hzcompute] " + text));
    }

    private static int parseMode(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "auto":
                return RfCore.COMPUTE_MODE_AUTO;
            case "cpu":
                return RfCore.COMPUTE_MODE_CPU;
            case "gpu":
                return RfCore.COMPUTE_MODE_GPU;
            default:
                return -1;
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case RfCore.COMPUTE_MODE_CPU:
                return "CPU";
            case RfCore.COMPUTE_MODE_GPU:
                return "GPU";
            case RfCore.COMPUTE_MODE_AUTO:
            default:
                return "AUTO";
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "stats", "zoomdft", "raycast", "threshold", "log");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("zoomdft") || sub.equals("raycast")) {
                return getListOfStringsMatchingLastWord(args, "auto", "cpu", "gpu");
            }
            if (sub.equals("log")) {
                return getListOfStringsMatchingLastWord(args, "on", "off");
            }
        }
        return null;
    }
}