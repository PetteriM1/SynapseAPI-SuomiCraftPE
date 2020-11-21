package org.itxtech.synapseapi;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.BatchPacketsEvent;
import cn.nukkit.network.RakNetInterface;
import cn.nukkit.network.SourceInterface;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.ConfigSection;
import org.itxtech.synapseapi.messaging.Messenger;
import org.itxtech.synapseapi.messaging.StandardMessenger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author boybook
 */
public class SynapseAPI extends PluginBase implements Listener {

    private static SynapseAPI instance;
    private final Map<String, SynapseEntry> synapseEntries = new HashMap<>();
    private Messenger messenger;
    public static boolean playerCountUpdates = false;
    public static Map<String, Integer> playerCountData = new ConcurrentHashMap<>();

    public static SynapseAPI getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        if (getServer().getName().equals("Nukkit PetteriM1 Edition")) {
            getServer().getLogger().error("This build of SynapseAPI cannot be used on Nukkit PetteriM1 Edition. Please download correct build.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        instance = this;

        this.getServer().getPluginManager().registerEvents(this, this);
        this.messenger = new StandardMessenger();
        this.loadEntries();
    }

    public Map<String, SynapseEntry> getSynapseEntries() {
        return synapseEntries;
    }

    public void addSynapseAPI(SynapseEntry entry) {
        this.synapseEntries.put(entry.getHash(), entry);
    }

    public SynapseEntry getSynapseEntry(String hash) {
        return this.synapseEntries.get(hash);
    }

    public void shutdownAll() {
        for (SynapseEntry entry : new ArrayList<>(this.synapseEntries.values())) {
            entry.shutdown();
        }
    }

    @Override
    public void onDisable() {
        this.shutdownAll();
    }

    public DataPacket getPacket(byte[] buffer) {
        DataPacket data = this.getServer().getNetwork().getPacket(buffer[0] == (byte) 0xfe ? (byte) 0xff : buffer[0]);

        if (data == null) {
            return null;
        }

        data.setBuffer(buffer, 1);
        return data;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void loadEntries() {
        this.saveDefaultConfig();

        for (SourceInterface sourceInterface : this.getServer().getNetwork().getInterfaces()) {
            if (sourceInterface instanceof RakNetInterface) {
                sourceInterface.shutdown();
            }
        }

        List entries = this.getConfig().getList("entries");

        for (Object entry : entries) {
            ConfigSection section = new ConfigSection((LinkedHashMap) entry);
            String serverIp = section.getString("server-ip", "127.0.0.1");
            int port = section.getInt("server-port", 10305);
            boolean isLobbyServer = section.getBoolean("isLobbyServer");
            boolean transfer = section.getBoolean("transferOnShutdown", true);
            String password = section.getString("password");
            String serverDescription = section.getString("description");
            this.addSynapseAPI(new SynapseEntry(this, serverIp, port, isLobbyServer, transfer, password, serverDescription));
        }
    }

    public Messenger getMessenger() {
        return messenger;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        if (sender instanceof SynapsePlayer) {
            SynapsePlayer p = (SynapsePlayer) sender;
            String c = cmd.getName().toLowerCase();
            if (c.equals("transfer") || c.equals("srv")) {
                if (args.length > 0) {
                    if (p.getSynapseEntry().getServerDescription().equals(args[0])) {
                        p.sendMessage("\u00A7cYou are already on this server");
                    } else {
                        if (!p.transferByDescription(args[0])) {
                            p.sendMessage("\u00A7cUnknown server");
                        }
                    }
                } else {
                    return false;
                }
            } else if (c.equals("hub") || c.equals("lobby")) {
                List<String> l = getConfig().getStringList("lobbies");
                if (l.size() == 0) return true;
                if (!l.contains(p.getSynapseEntry().getServerDescription()) && !p.getSynapseEntry().isLobbyServer()) {
                    p.transferByDescription(l.get(new SplittableRandom().nextInt(l.size())));
                } else {
                    p.sendMessage("\u00A7cYou are already on a lobby server");
                }
            }
        }
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String message = e.getQuitMessage().toString();
        if (message.equals("timeout") || message.equals("generic reason") || message.equals("client disconnect") || message.equals("unknown")) {
            e.setQuitMessage("");
        }
    }

    @EventHandler
    public void onBatchPackets(BatchPacketsEvent e) {
        e.setCancelled(true);
        Player[] players = e.getPlayers();
        DataPacket[] packets = e.getPackets();

        CompletableFuture.runAsync(() -> {
            for (Player p : players) {
                if (!(p instanceof SynapsePlayer)) continue;
                SynapsePlayer player = (SynapsePlayer) p;
                for (DataPacket pk : packets) {
                    player.sendDataPacket(pk, false, false);
                }
            }
        });
    }
}
