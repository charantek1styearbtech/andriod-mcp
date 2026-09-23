import mongoose, { Schema, Document } from 'mongoose';

export interface IUser extends Document {
  userId: string;
  email?: string;
  apiKey: string;
  name?: string;
  createdAt: Date;
}

export interface IDevice extends Document {
  deviceId: string;
  userId: string;
  email?: string;
  token: string;
  status: string;
  metadata?: Record<string, unknown>;
  lastSeenAt: Date;
  createdAt: Date;
}

export interface IAuditLog extends Document {
  requestId: string;
  userId: string;
  deviceId: string;
  email?: string;
  action: string;
  params?: Record<string, unknown>;
  success: boolean;
  durationMs: number;
  error?: string;
  timestamp: Date;
}

const UserSchema = new Schema<IUser>({
  userId: { type: String, required: true, unique: true, index: true },
  email: { type: String, index: true },
  apiKey: { type: String, required: true, index: true },
  name: { type: String },
  createdAt: { type: Date, default: Date.now },
});

const DeviceSchema = new Schema<IDevice>({
  deviceId: { type: String, required: true, unique: true, index: true },
  userId: { type: String, required: true, index: true },
  email: { type: String, index: true },
  token: { type: String, required: true },
  status: { type: String, default: 'OFFLINE' },
  metadata: { type: Schema.Types.Mixed },
  lastSeenAt: { type: Date, default: Date.now },
  createdAt: { type: Date, default: Date.now },
});

const AuditLogSchema = new Schema<IAuditLog>({
  requestId: { type: String, required: true, unique: true, index: true },
  userId: { type: String, required: true, index: true },
  deviceId: { type: String, required: true, index: true },
  email: { type: String, index: true },
  action: { type: String, required: true },
  params: { type: Schema.Types.Mixed },
  success: { type: Boolean, required: true },
  durationMs: { type: Number, required: true },
  error: { type: String },
  timestamp: { type: Date, default: Date.now, index: true },
});

export const UserModel = mongoose.models.User || mongoose.model<IUser>('User', UserSchema);
export const DeviceModel = mongoose.models.Device || mongoose.model<IDevice>('Device', DeviceSchema);
export const AuditLogModel = mongoose.models.AuditLog || mongoose.model<IAuditLog>('AuditLog', AuditLogSchema);

function sanitizeMongoUri(uri: string): string {
  try {
    const match = uri.match(/^(mongodb(?:\+srv)?:\/\/)([^:]+):(.*)@([^@]+)$/);
    if (match) {
      const [, proto, user, pass, hostAndOpts] = match;
      const decodedPass = decodeURIComponent(pass);
      const encodedPass = encodeURIComponent(decodedPass);
      return `${proto}${encodeURIComponent(decodeURIComponent(user))}:${encodedPass}@${hostAndOpts}`;
    }
  } catch {
    // If parsing fails, return original uri
  }
  return uri;
}

export class MongoDatabase {
  private isConnected = false;

  public async connect(): Promise<boolean> {
    const rawUri = process.env.MONGODB_URI || process.env.MONGO_URL;
    if (!rawUri) {
      console.log('[MongoDB] No MONGODB_URI configured. Running with in-memory persistence fallback.');
      return false;
    }

    const mongoUri = sanitizeMongoUri(rawUri);

    try {
      await mongoose.connect(mongoUri, {
        serverSelectionTimeoutMS: 5000,
      });
      this.isConnected = true;
      console.log('[MongoDB] Connected to MongoDB database successfully.');
      return true;
    } catch (err: unknown) {
      console.warn('[MongoDB] Failed to connect to MongoDB, falling back to in-memory mode:', (err as Error).message);
      this.isConnected = false;
      return false;
    }
  }

  public isAvailable(): boolean {
    return this.isConnected && mongoose.connection.readyState === 1;
  }

  public async recordAuditLog(log: {
    requestId: string;
    userId: string;
    deviceId: string;
    email?: string;
    action: string;
    params?: Record<string, unknown>;
    success: boolean;
    durationMs: number;
    error?: string;
  }): Promise<void> {
    if (!this.isAvailable()) return;

    try {
      await AuditLogModel.create({
        ...log,
        timestamp: new Date(),
      });
    } catch (err) {
      console.warn('[MongoDB] Failed to save audit log:', (err as Error).message);
    }
  }

  public async upsertDevice(device: {
    deviceId: string;
    userId: string;
    email?: string;
    token?: string;
    status: string;
    metadata?: Record<string, unknown>;
  }): Promise<void> {
    if (!this.isAvailable()) return;

    try {
      await DeviceModel.findOneAndUpdate(
        { deviceId: device.deviceId },
        {
          $set: {
            userId: device.userId,
            email: device.email,
            status: device.status,
            metadata: device.metadata,
            lastSeenAt: new Date(),
            ...(device.token ? { token: device.token } : {}),
          },
        },
        { upsert: true, new: true }
      );
    } catch (err) {
      console.warn('[MongoDB] Failed to upsert device in DB:', (err as Error).message);
    }
  }

  public async disconnect(): Promise<void> {
    if (this.isConnected) {
      await mongoose.disconnect();
      this.isConnected = false;
    }
  }
}

export const mongoDatabase = new MongoDatabase();
