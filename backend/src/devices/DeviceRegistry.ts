import type { WebSocket } from 'ws';
import { RegisteredDevice, DeviceState, DeviceMetadata, DeviceOutgoingMessage } from '../types/protocol.js';
import { redisClusterManager } from '../db/redis.js';
import { mongoDatabase, DeviceModel } from '../db/mongo.js';

export interface DeviceSession {
  device: RegisteredDevice;
  socket: WebSocket;
  pingTimer?: NodeJS.Timeout;
}

export class DeviceRegistry {
  // Map of deviceId -> DeviceSession
  private sessions: Map<string, DeviceSession> = new Map();
  // Map of email -> Set of deviceIds
  private emailToDevices: Map<string, Set<string>> = new Map();
  // Callbacks for device events
  private onDisconnectCallbacks: Array<(deviceId: string) => void> = [];

  constructor() {
    // Start periodic heartbeat checker every 15 seconds
    const heartbeatTimer = setInterval(() => this.runHeartbeatCheck(), 15000);
    if (heartbeatTimer.unref) {
      heartbeatTimer.unref();
    }
  }

  public register(
    deviceId: string,
    userId: string,
    socket: WebSocket,
    metadata: DeviceMetadata = {},
    email?: string
  ): RegisteredDevice {
    // If existing session exists for this deviceId, cleanly close old socket
    const existing = this.sessions.get(deviceId);
    if (existing) {
      try {
        existing.socket.close(1000, 'Replaced by new connection');
      } catch {
        // Ignore close errors
      }
    }

    const normalizedEmail = email ? email.toLowerCase().trim() : undefined;
    const now = Date.now();
    const registered: RegisteredDevice = {
      deviceId,
      userId,
      email: normalizedEmail,
      state: 'ONLINE',
      connectedAt: now,
      lastSeenAt: now,
      metadata,
    };

    const session: DeviceSession = {
      device: registered,
      socket,
    };

    this.sessions.set(deviceId, session);

    // Index by email
    if (normalizedEmail) {
      if (!this.emailToDevices.has(normalizedEmail)) {
        this.emailToDevices.set(normalizedEmail, new Set());
      }
      this.emailToDevices.get(normalizedEmail)!.add(deviceId);
    }

    // Sync to Redis presence & MongoDB long-term store
    redisClusterManager.registerDevice(deviceId, normalizedEmail, metadata);
    mongoDatabase.upsertDevice({
      deviceId,
      userId,
      email: normalizedEmail,
      status: 'ONLINE',
      metadata,
    });

    console.log(`[DeviceRegistry] Device connected: ${deviceId} (User: ${userId}, Email: ${normalizedEmail || 'none'}) on instance ${redisClusterManager.instanceId}`);
    return registered;
  }

  public unregister(deviceId: string): void {
    const session = this.sessions.get(deviceId);
    if (session) {
      const email = session.device.email;
      if (email && this.emailToDevices.has(email)) {
        this.emailToDevices.get(email)!.delete(deviceId);
        if (this.emailToDevices.get(email)!.size === 0) {
          this.emailToDevices.delete(email);
        }
      }

      this.sessions.delete(deviceId);
      redisClusterManager.unregisterDevice(deviceId, email);
      mongoDatabase.upsertDevice({
        deviceId,
        userId: session.device.userId,
        email,
        status: 'OFFLINE',
      });

      console.log(`[DeviceRegistry] Device disconnected: ${deviceId}`);
      for (const cb of this.onDisconnectCallbacks) {
        try {
          cb(deviceId);
        } catch (err) {
          console.error(`[DeviceRegistry] Error in disconnect callback for ${deviceId}:`, err);
        }
      }
    }
  }

  public onDisconnect(cb: (deviceId: string) => void): void {
    this.onDisconnectCallbacks.push(cb);
  }

  public getSession(deviceId: string): DeviceSession | undefined {
    return this.sessions.get(deviceId);
  }

  public getDevice(deviceId: string): RegisteredDevice | undefined {
    return this.sessions.get(deviceId)?.device;
  }

  public getDevicesForEmail(email: string): RegisteredDevice[] {
    const normalized = email.toLowerCase().trim();
    const deviceIds = this.emailToDevices.get(normalized);
    if (!deviceIds || deviceIds.size === 0) {
      return [];
    }
    const devices: RegisteredDevice[] = [];
    for (const devId of deviceIds) {
      const dev = this.getDevice(devId);
      if (dev) {
        devices.push(dev);
      }
    }
    return devices;
  }

  public getPrimaryDeviceForEmail(email: string): RegisteredDevice | undefined {
    const devices = this.getDevicesForEmail(email);
    // Find online device, otherwise first
    return devices.find((d) => this.isOnline(d.deviceId)) || devices[0];
  }

  public async getDevicesForEmailAsync(email: string): Promise<RegisteredDevice[]> {
    const local = this.getDevicesForEmail(email);
    if (!redisClusterManager.isAvailable()) {
      return local;
    }

    const deviceIds = await redisClusterManager.getDevicesForEmail(email);
    if (deviceIds.length === 0) {
      return local;
    }

    const results: RegisteredDevice[] = [];
    for (const devId of deviceIds) {
      const localDev = this.getDevice(devId);
      if (localDev) {
        results.push(localDev);
      } else {
        const info = await redisClusterManager.getDeviceInfo(devId);
        if (info) {
          let metadata: Record<string, unknown> = {};
          try {
            metadata = JSON.parse(info.metadata || '{}');
          } catch {}
          results.push({
            deviceId: devId,
            userId: `user_${email.replace(/[^a-zA-Z0-9]/g, '_')}`,
            email,
            state: (info.status as any) || 'ONLINE',
            connectedAt: parseInt(info.lastSeenAt || '0', 10) || Date.now(),
            lastSeenAt: parseInt(info.lastSeenAt || '0', 10) || Date.now(),
            metadata: metadata as any,
          });
        }
      }
    }
    return results.length > 0 ? results : local;
  }

  public listDevices(userId?: string): RegisteredDevice[] {
    const devices: RegisteredDevice[] = [];
    for (const session of this.sessions.values()) {
      if (!userId || session.device.userId === userId) {
        devices.push(session.device);
      }
    }
    return devices;
  }

  public async listDevicesAsync(userId?: string): Promise<RegisteredDevice[]> {
    const local = this.listDevices(userId);
    if (!mongoDatabase.isAvailable()) {
      return local;
    }

    try {
      const query = userId && userId !== 'user_anonymous' ? { userId } : {};
      const dbDevices = await DeviceModel.find(query).lean();
      if (dbDevices.length === 0) return local;

      return dbDevices.map((d) => ({
        deviceId: d.deviceId,
        userId: d.userId,
        email: d.email,
        state: (d.status as any) || 'ONLINE',
        connectedAt: d.createdAt ? new Date(d.createdAt).getTime() : Date.now(),
        lastSeenAt: d.lastSeenAt ? new Date(d.lastSeenAt).getTime() : Date.now(),
        metadata: (d.metadata as any) || {},
      }));
    } catch {
      return local;
    }
  }

  public isOnline(deviceId: string): boolean {
    const session = this.sessions.get(deviceId);
    return session !== undefined && session.socket.readyState === 1; // WebSocket.OPEN = 1
  }

  public async findDeviceInstance(deviceId: string): Promise<string | null> {
    if (this.isOnline(deviceId)) {
      return redisClusterManager.instanceId;
    }
    return await redisClusterManager.getDeviceInstance(deviceId);
  }

  public async isDeviceOnlineAsync(deviceId: string): Promise<boolean> {
    if (this.isOnline(deviceId)) return true;
    const instance = await redisClusterManager.getDeviceInstance(deviceId);
    return instance !== null;
  }

  public setDeviceState(deviceId: string, state: DeviceState): void {
    const session = this.sessions.get(deviceId);
    if (session) {
      session.device.state = state;
    }
  }

  public updateHeartbeat(deviceId: string): void {
    const session = this.sessions.get(deviceId);
    if (session) {
      session.device.lastSeenAt = Date.now();
    }
  }

  public sendToDevice(deviceId: string, msg: DeviceOutgoingMessage): boolean {
    const session = this.sessions.get(deviceId);
    if (!session || session.socket.readyState !== 1) {
      return false;
    }
    try {
      session.socket.send(JSON.stringify(msg));
      return true;
    } catch (err) {
      console.error(`[DeviceRegistry] Failed to send message to ${deviceId}:`, err);
      return false;
    }
  }

  private runHeartbeatCheck(): void {
    const now = Date.now();
    const timeoutThreshold = 45000; // 45 seconds without ping/pong -> consider stale

    for (const [deviceId, session] of this.sessions.entries()) {
      if (now - session.device.lastSeenAt > timeoutThreshold) {
        console.warn(`[DeviceRegistry] Device ${deviceId} heartbeat timed out (>45s). Disconnecting.`);
        try {
          session.socket.terminate();
        } catch {
          // ignore
        }
        this.unregister(deviceId);
      } else {
        // Send a ping
        this.sendToDevice(deviceId, {
          type: 'PING',
          timestamp: now,
        });
      }
    }
  }
}

export const deviceRegistry = new DeviceRegistry();
