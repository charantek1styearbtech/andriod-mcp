export interface AuthUser {
  userId: string;
  name: string;
  apiKey: string;
  email?: string;
}

export interface DeviceCredentials {
  deviceId: string;
  token: string;
  ownerUserId: string;
  email?: string;
}

export class AuthManager {
  // Map of apiKey -> AuthUser
  private userApiKeys: Map<string, AuthUser> = new Map();
  // Map of email -> AuthUser
  private userEmails: Map<string, AuthUser> = new Map();
  // Map of deviceId -> DeviceCredentials
  private deviceTokens: Map<string, DeviceCredentials> = new Map();

  constructor() {
    this.seedDefaultAuth();
  }

  private seedDefaultAuth(): void {
    // Seed default admin / demo user
    const defaultApiKey = process.env.MCP_API_KEY || 'mcp-user-secret-key-101';
    const defaultUserId = process.env.MCP_DEFAULT_USER_ID || 'user_101';

    this.registerUser({
      userId: defaultUserId,
      name: 'Default User',
      apiKey: defaultApiKey,
      email: 'default@gmail.com',
    });

    // Seed default device credentials (e.g. OnePlus Nord 4)
    const defaultDeviceId = process.env.MCP_DEFAULT_DEVICE_ID || 'oneplus_nord_4';
    const defaultDeviceToken = process.env.MCP_DEVICE_TOKEN || 'nord4_token_secure';

    this.registerDevice(defaultDeviceId, defaultDeviceToken, defaultUserId, 'default@gmail.com');
  }

  public registerUser(user: AuthUser): void {
    this.userApiKeys.set(user.apiKey, user);
    if (user.email) {
      this.userEmails.set(user.email.toLowerCase().trim(), user);
    }
  }

  public registerDevice(deviceId: string, token: string, ownerUserId: string, email?: string): void {
    this.deviceTokens.set(deviceId, {
      deviceId,
      token,
      ownerUserId,
      email: email ? email.toLowerCase().trim() : undefined,
    });
  }

  public authenticateUser(apiKey: string): AuthUser | null {
    if (!apiKey) return null;
    return this.userApiKeys.get(apiKey) || null;
  }

  public getUserByEmail(email: string): AuthUser | undefined {
    return this.userEmails.get(email.toLowerCase().trim());
  }

  public getOrCreateUserForEmail(email: string): AuthUser {
    const normalized = email.toLowerCase().trim();
    const existing = this.userEmails.get(normalized);
    if (existing) {
      return existing;
    }

    const userId = `user_${normalized.replace(/[^a-zA-Z0-9]/g, '_')}`;
    const newUser: AuthUser = {
      userId,
      name: normalized.split('@')[0],
      apiKey: `key_${normalized.replace(/[^a-zA-Z0-9]/g, '_')}`,
      email: normalized,
    };
    this.registerUser(newUser);
    return newUser;
  }

  public authenticateDevice(
    deviceId: string,
    token: string,
    email?: string
  ): { userId: string } | null {
    const normalizedEmail = email ? email.toLowerCase().trim() : undefined;
    const record = this.deviceTokens.get(deviceId);

    // If device presents an email, resolve/bind the owner to that Gmail account
    let ownerUserId = record?.ownerUserId;
    if (normalizedEmail) {
      const user = this.getOrCreateUserForEmail(normalizedEmail);
      ownerUserId = user.userId;
    }

    if (!record) {
      // Auto-provision device
      const defaultToken = process.env.MCP_DEVICE_TOKEN || 'nord4_token_secure';
      if (token === defaultToken || token.length >= 8) {
        const effectiveUserId = ownerUserId || process.env.MCP_DEFAULT_USER_ID || 'user_101';
        this.registerDevice(deviceId, token, effectiveUserId, normalizedEmail);
        return { userId: effectiveUserId };
      }
      return null;
    }

    if (record.token === token) {
      if (normalizedEmail && record.ownerUserId !== ownerUserId) {
        // Update device ownership to the authenticated Gmail account
        record.ownerUserId = ownerUserId!;
        record.email = normalizedEmail;
      }
      return { userId: record.ownerUserId };
    }

    return null;
  }

  public verifyDeviceOwnership(userId: string, deviceId: string): boolean {
    const creds = this.deviceTokens.get(deviceId);
    if (!creds) return false;
    return creds.ownerUserId === userId;
  }

  public getOwnerOfDevice(deviceId: string): string | null {
    const creds = this.deviceTokens.get(deviceId);
    return creds ? creds.ownerUserId : null;
  }
}

export const authManager = new AuthManager();
