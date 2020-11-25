package org.itxtech.synapseapi;

import cn.nukkit.AdventureSettings;
import cn.nukkit.AdventureSettings.Type;
import cn.nukkit.Player;
import cn.nukkit.PlayerFood;
import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.data.CommandDataVersions;
import cn.nukkit.event.entity.EntityMotionEvent;
import cn.nukkit.event.player.PlayerKickEvent;
import cn.nukkit.event.player.PlayerLoginEvent;
import cn.nukkit.item.Item;
import cn.nukkit.lang.TextContainer;
import cn.nukkit.level.Level;
import cn.nukkit.math.NukkitMath;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.network.SourceInterface;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.ContainerIds;
import cn.nukkit.potion.Effect;
import cn.nukkit.utils.TextFormat;
import org.itxtech.synapseapi.event.player.SynapseFullServerPlayerTransferEvent;
import org.itxtech.synapseapi.event.player.SynapsePlayerConnectEvent;
import org.itxtech.synapseapi.event.player.SynapsePlayerTransferEvent;
import org.itxtech.synapseapi.network.protocol.spp.PlayerLoginPacket;
import org.itxtech.synapseapi.runnable.TransferRunnable;
import org.itxtech.synapseapi.utils.ClientData;
import org.itxtech.synapseapi.utils.ClientData.Entry;
import org.itxtech.synapseapi.utils.DataPacketEidReplacer;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Created by boybook on 16/6/24.
 */
public class SynapsePlayer extends Player {

    public static final long REPLACE_ID = Long.MAX_VALUE;

    private static final Method updateName;

    public boolean isSynapseLogin;
    protected SynapseEntry synapseEntry;
    private boolean isFirstTimeLogin;

    static {
        try {
            updateName = Server.class.getDeclaredMethod("updateName", UUID.class, String.class);
            updateName.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public SynapsePlayer(SourceInterface interfaz, SynapseEntry synapseEntry, Long clientID, String ip, int port) {
        super(interfaz, clientID, ip, port);
        this.synapseEntry = synapseEntry;
        this.isSynapseLogin = this.synapseEntry != null;
    }

    private static int getClientFriendlyGamemode(int gamemode) {
        gamemode &= 0x03;
        if (gamemode == Player.SPECTATOR) {
            return Player.CREATIVE;
        }
        return gamemode;
    }

    public void handleLoginPacket(PlayerLoginPacket packet) {
        if (!this.isSynapseLogin) {
            super.handleDataPacket(SynapseAPI.getInstance().getPacket(packet.cachedLoginPacket));
            return;
        }
        this.isFirstTimeLogin = packet.isFirstTime;
        SynapsePlayerConnectEvent ev;
        this.server.getPluginManager().callEvent(ev = new SynapsePlayerConnectEvent(this, this.isFirstTimeLogin));
        if (!ev.isCancelled()) {
            DataPacket pk = SynapseAPI.getInstance().getPacket(packet.cachedLoginPacket);
            pk.setOffset(1);
            pk.decode();
            this.handleDataPacket(pk);
        }
    }

    public SynapseEntry getSynapseEntry() {
        return synapseEntry;
    }

    @Override
    protected void processLogin() {
        if (!this.isSynapseLogin) {
            super.processLogin();
            return;
        }

        if (!this.server.isWhitelisted((this.getName()).toLowerCase())) {
            this.kick(PlayerKickEvent.Reason.NOT_WHITELISTED, "Server is white-listed");
            return;
        } else if (this.isBanned()) {
            this.kick(PlayerKickEvent.Reason.NAME_BANNED, "You are banned");
            return;
        } else if (this.server.getIPBans().isBanned(this.getAddress())) {
            this.kick(PlayerKickEvent.Reason.IP_BANNED, "You are banned");
            return;
        }

        Player oldPlayer = null;
        for (Player p : new ArrayList<>(this.server.getOnlinePlayers().values())) {
            if (p != this && p.getName() != null && p.getName().equalsIgnoreCase(this.getName()) ||
                    this.getUniqueId().equals(p.getUniqueId())) {
                oldPlayer = p;
                break;
            }
        }

        CompoundTag nbt;
        if (oldPlayer != null) {
            oldPlayer.saveNBT();
            nbt = oldPlayer.namedTag;
            oldPlayer.close("", "disconnectionScreen.loggedinOtherLocation", false);
        } else {
            File legacyDataFile = new File(server.getDataPath() + "players/" + this.username.toLowerCase() + ".dat");
            File dataFile = new File(server.getDataPath() + "players/" + this.uuid + ".dat");
            if (legacyDataFile.exists() && !dataFile.exists()) {
                nbt = this.server.getOfflinePlayerData(this.username, false);

                if (!legacyDataFile.delete()) {
                    this.server.getLogger().alert("Could not delete legacy player data for " + this.username);
                }
            } else {
                nbt = this.server.getOfflinePlayerData(this.uuid, true);
            }
        }

        if (nbt == null) {
            this.close(this.getLeaveMessage(), "Invalid data");
            return;
        }

        if (getLoginChainData().isXboxAuthed() && server.getPropertyBoolean("xbox-auth") || !server.getPropertyBoolean("xbox-auth")) {
            try {
                updateName.invoke(server, this.uuid, this.username);
            } catch (IllegalAccessException | InvocationTargetException ignored) {}
        }

        this.playedBefore = (nbt.getLong("lastPlayed") - nbt.getLong("firstPlayed")) > 1;

        boolean alive = true;

        nbt.putString("NameTag", this.username);

        if (nbt.getShort("Health") < 1) {
            alive = false;
        }

        this.setExperience(nbt.getInt("EXP"), nbt.getInt("expLevel"));

        if (this.server.getForceGamemode()) {
            this.gamemode = this.server.getGamemode();
            nbt.putInt("playerGameType", this.gamemode);
        } else {
            this.gamemode = nbt.getInt("playerGameType") & 0x03;
        }

        this.adventureSettings = new AdventureSettings(this)
                .set(Type.WORLD_IMMUTABLE, isAdventure() || isSpectator())
                .set(Type.WORLD_BUILDER, !isAdventure() && !isSpectator())
                .set(Type.AUTO_JUMP, true)
                .set(Type.ALLOW_FLIGHT, isCreative())
                .set(Type.NO_CLIP, isSpectator());

        Level level;
        if ((level = this.server.getLevelByName(nbt.getString("Level"))) == null || !alive) {
            this.setLevel(this.server.getDefaultLevel());
            nbt.putString("Level", this.level.getName());
            nbt.getList("Pos", DoubleTag.class)
                    .add(new DoubleTag("0", this.level.getSpawnLocation().x))
                    .add(new DoubleTag("1", this.level.getSpawnLocation().y))
                    .add(new DoubleTag("2", this.level.getSpawnLocation().z));
        } else {
            this.setLevel(level);
        }

        for (Tag achievement : nbt.getCompound("Achievements").getAllTags()) {
            if (!(achievement instanceof ByteTag)) {
                continue;
            }

            if (((ByteTag) achievement).getData() > 0) {
                this.achievements.add(achievement.getName());
            }
        }

        nbt.putLong("lastPlayed", System.currentTimeMillis() / 1000);

        UUID uuid = getUniqueId();
        nbt.putLong("UUIDLeast", uuid.getLeastSignificantBits());
        nbt.putLong("UUIDMost", uuid.getMostSignificantBits());

        if (this.server.getAutoSave()) {
            this.server.saveOfflinePlayerData(this.uuid, nbt, true);
        }

        if (this.isFirstTimeLogin) {
            this.sendPlayStatus(PlayStatusPacket.LOGIN_SUCCESS);
        }

        ListTag<DoubleTag> posList = nbt.getList("Pos", DoubleTag.class);

        super.init(this.level.getChunk((int) posList.get(0).data >> 4, (int) posList.get(2).data >> 4, true), nbt);

        if (!this.namedTag.contains("foodLevel")) {
            this.namedTag.putInt("foodLevel", 20);
        }
        int foodLevel = this.namedTag.getInt("foodLevel");
        if (!this.namedTag.contains("FoodSaturationLevel")) {
            this.namedTag.putFloat("FoodSaturationLevel", 20);
        }
        float foodSaturationLevel = this.namedTag.getFloat("foodSaturationLevel");
        this.foodData = new PlayerFood(this, foodLevel, foodSaturationLevel);

        if (this.isSpectator()) this.keepMovement = true;

        this.forceMovement = this.teleportPosition = this.getPosition();

        if (this.isFirstTimeLogin) {
            ResourcePacksInfoPacket infoPacket = new ResourcePacksInfoPacket();
            infoPacket.resourcePackEntries = this.server.getResourcePackManager().getResourceStack();
            infoPacket.mustAccept = this.server.getForceResources();
            this.dataPacket(infoPacket);
        } else {
            this.shouldLogin = true;
        }
    }

    @Override
    protected void completeLoginSequence() {
        if (!this.isSynapseLogin) {
            super.completeLoginSequence();
            return;
        }

        PlayerLoginEvent ev;
        this.server.getPluginManager().callEvent(ev = new PlayerLoginEvent(this, "Plugin reason"));

        if (ev.isCancelled()) {
            this.close(this.getLeaveMessage(), ev.getKickMessage());
            return;
        }

        if (this.isFirstTimeLogin) {
            StartGamePacket startGamePacket = new StartGamePacket();
            startGamePacket.entityUniqueId = REPLACE_ID;
            startGamePacket.entityRuntimeId = REPLACE_ID;
            startGamePacket.playerGamemode = getClientFriendlyGamemode(this.gamemode);
            startGamePacket.x = (float) this.x;
            startGamePacket.y = (float) this.y;
            startGamePacket.z = (float) this.z;
            startGamePacket.yaw = (float) this.yaw;
            startGamePacket.pitch = (float) this.pitch;
            startGamePacket.dimension = 0/*(byte) (this.level.getDimension() & 0xff)*/;
            startGamePacket.worldGamemode = getClientFriendlyGamemode(this.gamemode);
            startGamePacket.difficulty = this.server.getDifficulty();
            startGamePacket.spawnX = (int) this.x;
            startGamePacket.spawnY = (int) this.y;
            startGamePacket.spawnZ = (int) this.z;
            startGamePacket.commandsEnabled = this.isEnableClientCommand();
            startGamePacket.worldName = this.getServer().getNetwork().getName();
            startGamePacket.gameRules = this.getLevel().getGameRules();
            this.dataPacket(startGamePacket);
        }

        this.loggedIn = true;

        this.server.getLogger().info(this.getServer().getLanguage().translateString("nukkit.player.logIn",
                TextFormat.AQUA + this.username + TextFormat.WHITE,
                this.ip,
                String.valueOf(this.port),
                String.valueOf(this.id),
                this.level.getName(),
                String.valueOf(NukkitMath.round(this.x, 4)),
                String.valueOf(NukkitMath.round(this.y, 4)),
                String.valueOf(NukkitMath.round(this.z, 4))));

        Map<UUID, Player> tempOnlinePlayers = getServer().getOnlinePlayers();
        boolean op = this.isOp();

        CompletableFuture.runAsync(() -> {
            try {
                if (!this.connected) return;
                if (this.isFirstTimeLogin) {
                    this.dataPacket(new BiomeDefinitionListPacket());
                    this.dataPacket(new AvailableEntityIdentifiersPacket());
                }

                this.getLevel().sendTime(this);

                SetDifficultyPacket diffucultyPK = new SetDifficultyPacket();
                diffucultyPK.difficulty = this.getServer().getDifficulty();
                this.dataPacket(diffucultyPK);
                SetCommandsEnabledPacket enableCommandsPK = new SetCommandsEnabledPacket();
                enableCommandsPK.enabled = this.isEnableClientCommand();
                this.dataPacket(enableCommandsPK);

                if (this.isEnableClientCommand()) {
                    this.getServer().getScheduler().scheduleDelayedTask(null, () -> {
                        if (this.isOnline()) {
                            this.sendCommandData();
                        }
                    }, 2);
                }

                this.adventureSettings.update();

                GameRulesChangedPacket gameRulesPK = new GameRulesChangedPacket();
                gameRulesPK.gameRules = level.getGameRules();
                this.dataPacket(gameRulesPK);

                sendFullPlayerListInternal(this, tempOnlinePlayers);

                this.sendAttributes();

                if (this.gamemode == Player.SPECTATOR) {
                    InventoryContentPacket inventoryContentPacket = new InventoryContentPacket();
                    inventoryContentPacket.inventoryId = ContainerIds.CREATIVE;
                    this.dataPacket(inventoryContentPacket);
                } else if (this.isFirstTimeLogin) {
                    this.inventory.sendCreativeContents();
                }

                this.sendAllInventories();
                this.inventory.sendHeldItem(this);

                if (this.isFirstTimeLogin) {
                    this.server.sendRecipeList(this);
                } else {
                    SetPlayerGameTypePacket pk = new SetPlayerGameTypePacket();
                    pk.gamemode = getClientFriendlyGamemode(gamemode);
                    this.dataPacket(pk);
                }

                this.sendPotionEffects(this);
                this.sendData(this);
                this.setCanClimb(true);
                this.setNameTagVisible(true);
                this.setNameTagAlwaysVisible(true);

                if (op || this.hasPermission("nukkit.textcolor")) {
                    this.setRemoveFormat(false);
                }
            } catch (Exception e) {
                this.close("", "Internal Server Error");
                getServer().getLogger().logException(e);
            }
        });

        this.server.addOnlinePlayer(this);

        ChunkRadiusUpdatedPacket chunkRadiusUpdatePacket = new ChunkRadiusUpdatedPacket();
        chunkRadiusUpdatePacket.radius = this.chunkRadius;
        this.dataPacket(chunkRadiusUpdatePacket);
    }

    private void sendFullPlayerListInternal(Player player, Map<UUID, Player> playerList) {
        PlayerListPacket pk = new PlayerListPacket();
        pk.type = PlayerListPacket.TYPE_ADD;
        pk.entries = playerList.values().stream()
                .map(p -> new PlayerListPacket.Entry(
                        p.getUniqueId(),
                        p.getId(),
                        p.getDisplayName(),
                        p.getSkin(),
                        p.getLoginChainData().getXUID()))
                .toArray(PlayerListPacket.Entry[]::new);
        player.dataPacket(pk);
    }

    public boolean transferByDescription(String serverDescription) {
        return this.transfer(this.getSynapseEntry().getClientData().getHashByDescription(serverDescription));
    }

    public boolean transfer(String hash) {
        return this.transfer(hash, true);
    }

    public boolean transfer(String hash, boolean loadScreen) {
        ClientData clients = this.getSynapseEntry().getClientData();
        Entry clientData = clients.clientList.get(hash);

        if (clientData != null) {
            SynapsePlayerTransferEvent event = new SynapsePlayerTransferEvent(this, clientData);
            this.server.getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return false;
            }

            this.clearEffects();
            this.clearInventory();
            new TransferRunnable(this, hash).run();
            return true;
        }

        return false;
    }

    private void clearEffects() {
        for (Effect e : this.getEffects().values()) {
            MobEffectPacket pk = new MobEffectPacket();
            pk.eid = this.getId();
            pk.effectId = e.getId();
            pk.eventId = MobEffectPacket.EVENT_REMOVE;
            this.dataPacket(pk);
        }
    }

    private void clearInventory() {
        if (this.inventory != null) {
            InventoryContentPacket pk = new InventoryContentPacket();
            pk.inventoryId = this.getWindowId(this.inventory);
            pk.slots = new Item[this.inventory.getSize()];
            this.dataPacket(pk);
        }
    }

    public void setUniqueId(UUID uuid) {
        this.uuid = uuid;
    }

    public void sendCommandData() {
        AvailableCommandsPacket pk = new AvailableCommandsPacket();
        Map<String, CommandDataVersions> data = new HashMap<>();
        for (Command command : this.server.getCommandMap().getCommands().values()) {
            if (!command.testPermissionSilent(this)) {
                continue;
            }
            CommandDataVersions data0 = command.generateCustomCommandData(this);
            data.put(command.getName(), data0);
        }
        pk.commands = data;
        this.dataPacket(pk, true);
    }

    @Override
    public int dataPacket(DataPacket packet, boolean needACK) {
        if (!this.isSynapseLogin) return super.dataPacket(packet, needACK);
        return sendDataPacket(packet, needACK, false);
    }

    @Override
    public int directDataPacket(DataPacket packet, boolean needACK) {
        if (!this.isSynapseLogin) return super.directDataPacket(packet, needACK);
        return sendDataPacket(packet, needACK, true);
    }

    public int sendDataPacket(DataPacket packet, boolean needACK, boolean direct) {
        packet = DataPacketEidReplacer.replace(packet, this.getId(), REPLACE_ID);
        /*DataPacketSendEvent ev = new DataPacketSendEvent(this, packet);
        this.server.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) {
            return -1;
        }*/

        if (!packet.isEncoded) {
            packet.encode();
            packet.isEncoded = true;
        }

        return this.interfaz.putPacket(this, packet, needACK, direct);
    }

    @Override
    public boolean setMotion(Vector3 motion) {
        if (!this.justCreated) {
            EntityMotionEvent ev = new EntityMotionEvent(this, motion);
            this.server.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) {
                return false;
            }
        }

        if (this.chunk != null && this.spawned) {
            this.addMotion(this.motionX, this.motionY, this.motionZ);
            SetEntityMotionPacket pk = new SetEntityMotionPacket();
            pk.eid = this.id;
            pk.motionX = (float) motion.x;
            pk.motionY = (float) motion.y;
            pk.motionZ = (float) motion.z;
            this.dataPacket(pk);
        }

        if (this.motionY > 0) {
            this.startAirTicks = (int) ((-(Math.log(this.getGravity() / (this.getGravity() + this.getDrag() * this.motionY))) / this.getDrag()) * 2 + 5);
        }

        return true;
    }

    // HACK: Transfer players to lobby on server shutdown
    // Needed since kicking players moved to happen before disabling plugins
    // Remove this when something like ServerStopEvent gets implemented
    @Override
    public void close(TextContainer message, String reason, boolean notify) {
        if (!reason.equals(this.getServer().getConfig().getString("settings.shutdown-message", "Server closed"))) {
            super.close(message, reason, notify);
        } else {
            List<String> l = SynapseAPI.getInstance().getConfig().getStringList("lobbies");
            int size = l.size();
            if (size == 0) {
                super.close(message, reason, notify);
                return;
            }
            SynapseAPI.getInstance().getLogger().info("Server shutdown detected. Transferring players...");
            SplittableRandom r = new SplittableRandom();
            for (Player p : this.getServer().getOnlinePlayers().values()) {
                if (p instanceof SynapsePlayer) {
                    p.sendMessage("\u00A7cServer went down");
                    ((SynapsePlayer) p).transferByDescription(l.get(size == 1 ? 0 : r.nextInt(size)));
                } else {
                    super.close(message, reason, notify);
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
            for (Player p : this.getServer().getOnlinePlayers().values()) {
                p.close("", "Already transferred", false);
            }
        }
    }

    // HACK: Transfer players to lobby when the server is full
    @Override
    public boolean kick(PlayerKickEvent.Reason reason, String reasonString, boolean isAdmin) {
        if (PlayerKickEvent.Reason.SERVER_FULL == reason) {
            SynapseFullServerPlayerTransferEvent event = new SynapseFullServerPlayerTransferEvent(this);
            this.server.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
            List<String> l = SynapseAPI.getInstance().getConfig().getStringList("lobbies");
            int size = l.size();
            if (size == 0) {
                return super.kick(reason, reasonString, isAdmin);
            }
            if (this.getSynapseEntry().getServerDescription().equals("2b2t") && this.transferByDescription("2b2t-queue")) {
                this.sendMessage("\u00A7cError");
            } else if (this.getSynapseEntry().getServerDescription().equals("cpe") && this.transferByDescription("cpe-queue")) {
                this.sendMessage("\u00A7cError");
            } else {
                this.sendMessage("\u00A7cServer is full");
                if (!this.transferByDescription(l.get(size == 1 ? 0 : new SplittableRandom().nextInt(size)))) {
                    return super.kick(reason, reasonString, isAdmin);
                }
            }
            this.getServer().getScheduler().scheduleDelayedTask(null, () -> this.close("", "Already transferred", false), 20);
            return false;
        }

        return super.kick(reason, reasonString, isAdmin);
    }
}
