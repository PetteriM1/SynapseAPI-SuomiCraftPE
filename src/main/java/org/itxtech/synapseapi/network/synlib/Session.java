package org.itxtech.synapseapi.network.synlib;

import io.netty.channel.Channel;
import org.itxtech.synapseapi.network.protocol.spp.SynapseDataPacket;

import java.net.InetSocketAddress;

public class Session {

    public Channel channel;
    private String ip;
    private int port;
    private SynapseClient client;
    private long lastCheck;
    private boolean connected;
    private long tickUseTime;

    public Session(SynapseClient client) {
        this.client = client;
        this.connected = true;
        this.lastCheck = System.currentTimeMillis();
    }

    public void updateAddress(InetSocketAddress address) {
        this.ip = address.getAddress().getHostAddress();
        this.port = address.getPort();
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public void run() {
        this.tickProcessor();
    }

    private void tickProcessor() {
        while (!this.client.isShutdown()) {
            long start = System.currentTimeMillis();
            try {
                this.tick();

            } catch (Exception e) {
                e.printStackTrace();
            }
            long time = System.currentTimeMillis() - start;
            this.tickUseTime = time;
            if (time < 10) {
                try {
                    Thread.sleep(10 - time);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (this.connected) {
            this.client.getClientGroup().shutdownGracefully();
        }
    }

    private void tick() throws Exception {
        if (this.update()) {
            int sendLen = 0;
            do {
                int len = this.sendPacket();
                if (len > 0) {
                    sendLen += len;
                } else {
                    break;
                }
            } while (sendLen < 65536);
        }
    }

    private int sendPacket() throws Exception {
        SynapseDataPacket packet = this.client.readMainToThreadPacket();
        if (packet != null) {
            this.writePacket(packet);
            return packet.getBuffer().length;
        }
        return -1;
    }

    public String getHash() {
        return this.getIp() + ':' + this.getPort();
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public Channel getChannel() {
        return channel;
    }

    public boolean update() throws Exception {
        if (this.client.needReconnect && this.connected) {
            this.connected = false;
            this.client.needReconnect = false;
        }
        if (!this.connected && !this.client.isShutdown()) {
            long time;
            if ((time = System.currentTimeMillis()) - this.lastCheck >= 3000) {
                this.client.getLogger().notice("Trying to re-connect to Synapse Server");
                if (this.client.connect()) {
                    this.connected = true;
                    this.client.setConnected(true);
                    this.client.setNeedAuth(true);
                }
                this.lastCheck = time;
            }
            return false;
        }
        return true;
    }

    public void writePacket(SynapseDataPacket pk) {
        if (this.channel != null) {
            this.channel.writeAndFlush(pk);
        }
    }

    public float getTicksPerSecond() {
        long more = this.tickUseTime - 10;
        if (more < 0) return 100;
        return Math.round(10f / this.tickUseTime) * 100;
    }
}
