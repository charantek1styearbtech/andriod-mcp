import { z } from 'zod';

export type JobStatus =
  | 'QUEUED'
  | 'SENT'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'TIMEOUT'
  | 'CANCELLED';

export type DeviceState = 'ONLINE' | 'BUSY' | 'OFFLINE';

export interface DeviceMetadata {
  model?: string;
  manufacturer?: string;
  osVersion?: string;
  appVersion?: string;
  screenWidth?: number;
  screenHeight?: number;
  batteryLevel?: number;
  currentPackage?: string;
  [key: string]: unknown;
}

export interface RegisteredDevice {
  deviceId: string;
  userId: string;
  email?: string;
  state: DeviceState;
  connectedAt: number;
  lastSeenAt: number;
  metadata: DeviceMetadata;
}

export interface McpJob<TParams = any, TResult = any> {
  requestId: string;
  userId: string;
  email?: string;
  deviceId: string;
  action: string;
  params: TParams;
  status: JobStatus;
  createdAt: number;
  startedAt?: number;
  completedAt?: number;
  timeoutMs: number;
  originInstanceId?: string;
  result?: TResult;
  error?: string;
}

// Wire messages between Backend and Device over WebSocket
export type DeviceIncomingMessage =
  | {
      type: 'AUTH';
      deviceId: string;
      token: string;
      email?: string;
      metadata?: DeviceMetadata;
    }
  | {
      type: 'PONG';
      timestamp: number;
    }
  | {
      type: 'ACTION_ACK';
      requestId: string;
      deviceId: string;
      status: 'RECEIVED' | 'RUNNING';
    }
  | {
      type: 'ACTION_RESULT';
      requestId: string;
      deviceId: string;
      success: boolean;
      result?: unknown;
      error?: string;
    }
  | {
      type: 'DEVICE_STATUS';
      deviceId: string;
      state: DeviceState;
      batteryLevel?: number;
      currentPackage?: string;
    };

export type DeviceOutgoingMessage =
  | {
      type: 'AUTH_ACK';
      success: boolean;
      deviceId?: string;
      error?: string;
    }
  | {
      type: 'PING';
      timestamp: number;
    }
  | {
      type: 'EXECUTE_ACTION';
      requestId: string;
      action: string;
      params: Record<string, unknown>;
      timeoutMs?: number;
    }
  | {
      type: 'CANCEL_ACTION';
      requestId: string;
    };

export const AuthHandshakeSchema = z.object({
  type: z.literal('AUTH'),
  deviceId: z.string().min(1),
  token: z.string().min(1),
  metadata: z.record(z.unknown()).optional(),
});
