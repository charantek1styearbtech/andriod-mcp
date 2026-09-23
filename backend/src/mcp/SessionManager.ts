import { v4 as uuidv4 } from 'uuid';
import { redisClusterManager } from '../db/redis.js';

export interface McpSession {
  sessionId: string;
  userId: string;
  email?: string;
  isOAuthVerified?: boolean;
  activeDeviceId?: string;
  createdAt: number;
  lastActiveAt: number;
}

export class SessionManager {
  private sessions: Map<string, McpSession> = new Map();
  // Session TTL: 24 hours of inactivity
  private readonly TTL_MS = 24 * 60 * 60 * 1000;

  constructor() {
    // Periodically clean up expired sessions without holding the event loop
    const cleanupTimer = setInterval(() => this.cleanupExpired(), 15 * 60 * 1000);
    cleanupTimer.unref();
  }

  /**
   * Retrieve an existing session or create a new one.
   */
  public getOrCreateSession(
    sessionId?: string,
    defaultUserId: string = 'user_anonymous',
    defaultEmail?: string,
    isOAuthVerified: boolean = false
  ): McpSession {
    const id = sessionId && sessionId.trim() ? sessionId.trim() : uuidv4();
    let session = this.sessions.get(id);

    if (!session) {
      session = {
        sessionId: id,
        userId: defaultUserId,
        email: defaultEmail?.toLowerCase().trim(),
        isOAuthVerified,
        createdAt: Date.now(),
        lastActiveAt: Date.now(),
      };
      this.sessions.set(id, session);
    } else {
      session.lastActiveAt = Date.now();
      if (defaultEmail && !session.email) {
        session.email = defaultEmail.toLowerCase().trim();
      }
      if (defaultUserId && session.userId === 'user_anonymous') {
        session.userId = defaultUserId;
      }
      if (isOAuthVerified) {
        session.isOAuthVerified = true;
      }
    }

    return session;
  }

  public getSession(sessionId: string): McpSession | undefined {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.lastActiveAt = Date.now();
    }
    return session;
  }

  public setAuthenticatedUser(
    sessionId: string,
    userId: string,
    email?: string,
    isOAuthVerified: boolean = false
  ): McpSession {
    let session = this.sessions.get(sessionId);
    if (!session) {
      session = {
        sessionId,
        userId,
        email: email?.toLowerCase().trim(),
        isOAuthVerified,
        createdAt: Date.now(),
        lastActiveAt: Date.now(),
      };
      this.sessions.set(sessionId, session);
    } else {
      session.userId = userId;
      if (email) session.email = email.toLowerCase().trim();
      if (isOAuthVerified) session.isOAuthVerified = true;
      session.lastActiveAt = Date.now();
    }
    return session;
  }

  public async setVerifiedSessionAsync(sessionId: string, email: string): Promise<McpSession> {
    const normalizedEmail = email.toLowerCase().trim();
    let session = this.sessions.get(sessionId);
    if (!session) {
      session = {
        sessionId,
        userId: `user_${normalizedEmail.replace(/[^a-zA-Z0-9]/g, '_')}`,
        email: normalizedEmail,
        isOAuthVerified: true,
        createdAt: Date.now(),
        lastActiveAt: Date.now(),
      };
      this.sessions.set(sessionId, session);
    } else {
      session.email = normalizedEmail;
      session.isOAuthVerified = true;
      session.lastActiveAt = Date.now();
    }

    // Persist verified session to Redis so multiple cluster nodes recognize it
    await redisClusterManager.setVerifiedSession(sessionId, normalizedEmail);
    return session;
  }

  public async getVerifiedEmailAsync(sessionId: string): Promise<string | undefined> {
    const session = this.sessions.get(sessionId);
    if (session?.isOAuthVerified && session.email) {
      return session.email;
    }

    // Check Redis in case OAuth callback was handled on another cluster node
    const redisVerifiedEmail = await redisClusterManager.getVerifiedSession(sessionId);
    if (redisVerifiedEmail) {
      if (session) {
        session.email = redisVerifiedEmail;
        session.isOAuthVerified = true;
      } else {
        this.getOrCreateSession(sessionId, undefined, redisVerifiedEmail, true);
      }
      return redisVerifiedEmail;
    }

    return undefined;
  }

  public async isSessionVerifiedAsync(sessionId: string): Promise<boolean> {
    const email = await this.getVerifiedEmailAsync(sessionId);
    return !!email;
  }

  public setActiveDevice(sessionId: string, deviceId: string): void {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.activeDeviceId = deviceId;
      session.lastActiveAt = Date.now();
    }
  }

  public async setActiveDeviceAsync(sessionId: string, deviceId: string, email?: string): Promise<void> {
    this.setActiveDevice(sessionId, deviceId);
    await redisClusterManager.setActiveDevice(sessionId, deviceId, email);
  }

  public getActiveDevice(sessionId: string): string | undefined {
    return this.sessions.get(sessionId)?.activeDeviceId;
  }

  public async getActiveDeviceAsync(sessionId: string): Promise<string | undefined> {
    const local = this.getActiveDevice(sessionId);
    if (local) return local;

    const fromRedis = await redisClusterManager.getActiveDevice(sessionId);
    if (fromRedis) {
      this.setActiveDevice(sessionId, fromRedis);
      return fromRedis;
    }
    return undefined;
  }

  public async getActiveDeviceForEmailAsync(email: string): Promise<string | undefined> {
    const fromRedis = await redisClusterManager.getActiveDeviceForEmail(email);
    return fromRedis || undefined;
  }

  public removeSession(sessionId: string): void {
    this.sessions.delete(sessionId);
  }

  private cleanupExpired(): void {
    const now = Date.now();
    for (const [id, session] of this.sessions.entries()) {
      if (now - session.lastActiveAt > this.TTL_MS) {
        this.sessions.delete(id);
      }
    }
  }
}

export const sessionManager = new SessionManager();
