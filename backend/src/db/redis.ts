import { Redis } from 'ioredis';
import { v4 as uuidv4 } from 'uuid';

export interface CrossInstanceJobPayload {
  requestId: string;
  userId: string;
  deviceId: string;
  action: string;
  params: Record<string, unknown>;
  originInstanceId: string;
  timeoutMs: number;
}

export interface CrossInstanceResultPayload {
  requestId: string;
  deviceId: string;
  success: boolean;
  result?: unknown;
  error?: string;
}

function formatRedisUrl(url: string): string {
  let cleaned = url.trim();
  const match = cleaned.match(/(rediss?:\/\/[^\s"']+)/);
  if (match) {
    cleaned = match[1];
  }
  if (cleaned.startsWith('redis://') && cleaned.includes('upstash.io')) {
    cleaned = cleaned.replace(/^redis:\/\//, 'rediss://');
  }
  return cleaned;
}

export class RedisClusterManager {
  public readonly instanceId: string;
  private client: Redis | null = null;
  private pubClient: Redis | null = null;
  private subClient: Redis | null = null;
  private isConnected: boolean = false;
  private jobResultWaiters: Map<
    string,
    {
      resolve: (res: CrossInstanceResultPayload) => void;
      reject: (err: Error) => void;
      timer: NodeJS.Timeout;
    }
  > = new Map();
  private instanceJobHandler: ((job: CrossInstanceJobPayload) => Promise<void>) | null = null;

  constructor() {
    this.instanceId = process.env.INSTANCE_ID || `node_${uuidv4().substring(0, 8)}`;
  }

  public async connect(): Promise<boolean> {
    const rawUrl = process.env.REDIS_URL;
    if (!rawUrl) {
      console.log(`[Redis] REDIS_URL not configured. Running in standalone in-memory mode.`);
      return false;
    }

    const redisUrl = formatRedisUrl(rawUrl);

    try {
      this.client = new Redis(redisUrl, {
        maxRetriesPerRequest: 1,
        retryStrategy: (times) => (times > 3 ? null : Math.min(times * 100, 2000)),
        lazyConnect: true,
      });

      this.pubClient = new Redis(redisUrl, {
        maxRetriesPerRequest: 1,
        lazyConnect: true,
      });

      this.subClient = new Redis(redisUrl, {
        maxRetriesPerRequest: 1,
        lazyConnect: true,
      });

      await Promise.all([
        this.client.connect(),
        this.pubClient.connect(),
        this.subClient.connect(),
      ]);

      // Subscribe to all job result return channels using pattern matching
      await this.subClient.psubscribe('job:res:*');
      this.subClient.on('pmessage', (_pattern, channel, message) => {
        const requestId = channel.replace('job:res:', '');
        const waiter = this.jobResultWaiters.get(requestId);
        if (waiter) {
          clearTimeout(waiter.timer);
          this.jobResultWaiters.delete(requestId);
          try {
            const parsed = JSON.parse(message) as CrossInstanceResultPayload;
            waiter.resolve(parsed);
          } catch (err) {
            waiter.reject(err as Error);
          }
        }
      });

      const instanceJobChannel = `instance:${this.instanceId}:jobs`;
      await this.subClient.subscribe(instanceJobChannel);
      console.log(`[Redis] Instance ${this.instanceId} subscribed to channel ${instanceJobChannel}`);

      this.subClient.on('message', async (chan, message) => {
        if (chan === instanceJobChannel && this.instanceJobHandler) {
          try {
            const job = JSON.parse(message) as CrossInstanceJobPayload;
            await this.instanceJobHandler(job);
          } catch (err) {
            console.error(`[Redis] Error processing job from channel ${instanceJobChannel}:`, err);
          }
        }
      });

      this.isConnected = true;
      console.log(`[Redis] Instance ${this.instanceId} successfully connected to Redis at ${redisUrl}`);
      return true;
    } catch (err) {
      console.warn(`[Redis] Could not connect to Redis, falling back to in-memory mode:`, (err as Error).message);
      this.isConnected = false;
      this.client = null;
      this.pubClient = null;
      this.subClient = null;
      return false;
    }
  }

  public isAvailable(): boolean {
    return this.isConnected && this.client !== null && this.client.status === 'ready';
  }

  // ----------------------------------------------------
  // Key-Value: Device Presence & Location Registry
  // ----------------------------------------------------
  public async registerDevice(
    deviceId: string,
    email?: string,
    metadata?: Record<string, unknown>
  ): Promise<void> {
    if (!this.isAvailable()) return;

    try {
      const pipeline = this.client!.pipeline();
      pipeline.set(`device:${deviceId}:instance`, this.instanceId, 'EX', 86400);
      pipeline.hset(`device:${deviceId}:info`, {
        deviceId,
        instanceId: this.instanceId,
        email: email || '',
        status: 'ONLINE',
        lastSeenAt: Date.now().toString(),
        metadata: JSON.stringify(metadata || {}),
      });

      if (email) {
        pipeline.sadd(`email:${email.toLowerCase().trim()}:devices`, deviceId);
      }

      await pipeline.exec();
    } catch (err) {
      console.warn(`[Redis] Error registering device ${deviceId}:`, (err as Error).message);
    }
  }

  public async unregisterDevice(deviceId: string, email?: string): Promise<void> {
    if (!this.isAvailable()) return;

    try {
      const pipeline = this.client!.pipeline();
      pipeline.del(`device:${deviceId}:instance`);
      pipeline.hset(`device:${deviceId}:info`, 'status', 'OFFLINE');
      if (email) {
        pipeline.srem(`email:${email.toLowerCase().trim()}:devices`, deviceId);
      }
      await pipeline.exec();
    } catch (err) {
      console.warn(`[Redis] Error unregistering device ${deviceId}:`, (err as Error).message);
    }
  }

  public async getDeviceInstance(deviceId: string): Promise<string | null> {
    if (!this.isAvailable()) return null;

    try {
      return await this.client!.get(`device:${deviceId}:instance`);
    } catch {
      return null;
    }
  }

  public async getDevicesForEmail(email: string): Promise<string[]> {
    if (!this.isAvailable()) return [];

    try {
      return await this.client!.smembers(`email:${email.toLowerCase().trim()}:devices`);
    } catch {
      return [];
    }
  }

  public async getDeviceInfo(deviceId: string): Promise<Record<string, string> | null> {
    if (!this.isAvailable()) return null;

    try {
      const data = await this.client!.hgetall(`device:${deviceId}:info`);
      return Object.keys(data).length > 0 ? data : null;
    } catch {
      return null;
    }
  }

  public async setActiveDevice(sessionId: string, deviceId: string, email?: string): Promise<void> {
    if (!this.isAvailable()) return;

    try {
      const pipeline = this.client!.pipeline();
      pipeline.set(`session:${sessionId}:activeDevice`, deviceId, 'EX', 86400);
      if (email) {
        pipeline.set(`email:${email.toLowerCase().trim()}:activeDevice`, deviceId, 'EX', 86400 * 7);
      }
      await pipeline.exec();
    } catch (err) {
      console.warn(`[Redis] Error setting active device:`, (err as Error).message);
    }
  }

  public async getActiveDevice(sessionId: string): Promise<string | null> {
    if (!this.isAvailable()) return null;

    try {
      return await this.client!.get(`session:${sessionId}:activeDevice`);
    } catch {
      return null;
    }
  }

  public async getActiveDeviceForEmail(email: string): Promise<string | null> {
    if (!this.isAvailable()) return null;

    try {
      return await this.client!.get(`email:${email.toLowerCase().trim()}:activeDevice`);
    } catch {
      return null;
    }
  }

  // ----------------------------------------------------
  // Pub/Sub: Cross-Instance Command Dispatch & Result Return
  // ----------------------------------------------------
  public async publishJobToInstance(targetInstanceId: string, job: CrossInstanceJobPayload): Promise<void> {
    if (!this.isAvailable()) {
      throw new Error(`Cannot dispatch cross-instance job: Redis is offline`);
    }

    const channel = `instance:${targetInstanceId}:jobs`;
    await this.pubClient!.publish(channel, JSON.stringify(job));
  }

  public async publishJobResult(requestId: string, result: CrossInstanceResultPayload): Promise<void> {
    if (!this.isAvailable()) return;

    const channel = `job:res:${requestId}`;
    await this.pubClient!.publish(channel, JSON.stringify(result));
  }

  public async subscribeToJobResult(
    requestId: string,
    timeoutMs: number
  ): Promise<CrossInstanceResultPayload> {
    if (!this.isAvailable()) {
      throw new Error(`Cannot wait for cross-instance result: Redis is offline`);
    }

    return new Promise<CrossInstanceResultPayload>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.jobResultWaiters.delete(requestId);
        reject(new Error(`Timeout waiting for cross-instance result (${timeoutMs}ms)`));
      }, timeoutMs);

      this.jobResultWaiters.set(requestId, { resolve, reject, timer });
    });
  }


  public async listenForInstanceJobs(
    handler: (job: CrossInstanceJobPayload) => Promise<void>
  ): Promise<void> {
    this.instanceJobHandler = handler;
  }

  public async disconnect(): Promise<void> {
    if (this.client) await this.client.quit();
    if (this.pubClient) await this.pubClient.quit();
    if (this.subClient) await this.subClient.quit();
    this.isConnected = false;
  }
}

export const redisClusterManager = new RedisClusterManager();
